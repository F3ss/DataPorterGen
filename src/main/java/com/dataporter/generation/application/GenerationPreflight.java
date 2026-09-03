package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationSourceInspection;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.TemplateFacts;
import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.generation.domain.error.GenerationException;
import com.dataporter.generation.ports.out.GenerationBsonEngine;
import com.dataporter.generation.ports.out.GenerationProgressReporter;
import com.dataporter.generation.ports.out.GenerationTarget;
import com.dataporter.generation.ports.out.TemplateCatalog;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.error.ConfigurationException;
import com.dataporter.shared.error.OperationCancelledException;
import com.dataporter.shared.error.TargetPreparationException;
import com.dataporter.shared.ports.out.CancellationToken;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class GenerationPreflight {
    private final GenerationTarget target;
    private final GenerationBsonEngine bson;
    private final GenerationProgressReporter progress;
    private final CancellationToken cancellation;
    private final TemplateSelector templateSelector = new TemplateSelector();
    private final IdRandomnessAnalyzer idRandomness = new IdRandomnessAnalyzer();

    GenerationPreflight(GenerationTarget target, GenerationBsonEngine bson,
                        GenerationProgressReporter progress, CancellationToken cancellation) {
        this.target = target; this.bson = bson; this.progress = progress; this.cancellation = cancellation;
    }

    void validateSourceCollections(GenerationSpec spec, GenerationSourceInspection inspection) {
        Set<String> collections = new HashSet<>(inspection.collections());
        Set<String> views = new HashSet<>(inspection.views());
        for (CollectionGenerationSpec requested : spec.collections()) {
            if (views.contains(requested.name())) throw new ConfigurationException("Generation source is a view, not an ordinary collection: " + requested.name());
            if (!collections.contains(requested.name())) throw new ConfigurationException("Unknown generation source collection: " + requested.name());
        }
    }

    void validateTargetCollections(GenerationSpec spec, Set<String> targetCollections) {
        List<String> missing = spec.collections().stream().map(CollectionGenerationSpec::name).filter(name -> !targetCollections.contains(name)).toList();
        if (!missing.isEmpty()) throw new TargetPreparationException("Target ordinary collections must already exist: " + missing);
    }

    void resolveIds(GenerationSpec spec, TemplateCatalog catalog, Map<String,ResolvedIdStrategy> ids,
                    Map<String,RandomStringId> randomStringIds, Map<String,Long> sequenceStarts,
                    Map<String,IdRandomnessAnalyzer.Analysis> randomAnalyses,
                    List<String> warnings, List<String> preWriteWarnings) {
        resolveStrategies(spec, catalog, ids, sequenceStarts);
        resolveRandomStringIds(spec, ids, randomStringIds);
        Map<String,CollectionGenerationSpec> ordered = new LinkedHashMap<>();
        for (CollectionGenerationSpec collection : spec.collections()) {
            ordered.put(collection.name(), collection);
            validateExplicitIdRule(collection, ordered);
            IdRandomnessAnalyzer.Analysis analysis = idRandomness.analyze(collection, ordered);
            randomAnalyses.put(collection.name(), analysis);
            if (analysis.hasRandom()) {
                String warning = analysis.warning(collection.name(), collection.count());
                warnings.add(warning);
                preWriteWarnings.add(warning);
            }
        }
    }

    private void resolveStrategies(GenerationSpec spec, TemplateCatalog catalog,
                                   Map<String,ResolvedIdStrategy> ids, Map<String,Long> sequenceStarts) {
        for (CollectionGenerationSpec collection : spec.collections()) {
            ResolvedIdStrategy strategy;
            if (collection.fields().containsKey("/_id")) strategy = ResolvedIdStrategy.explicit();
            else {
                Set<TemplateFacts.IdKind> kinds = EnumSet.noneOf(TemplateFacts.IdKind.class);
                Set<String> equal = null;
                for (long i = 0; i < catalog.count(collection.name()); i++) {
                    TemplateFacts facts = bson.inspect(catalog.get(collection.name(), i)); kinds.add(facts.idKind());
                    equal = equal == null ? new LinkedHashSet<>(facts.scalarPathsEqualToId()) : intersection(equal, facts.scalarPathsEqualToId());
                }
                if (equal != null && equal.size() == 1) strategy = new ResolvedIdStrategy(ResolvedIdStrategy.Kind.FIELD_REFERENCE, equal.iterator().next(), 0);
                else if (kinds.equals(Set.of(TemplateFacts.IdKind.OBJECT_ID))) strategy = new ResolvedIdStrategy(ResolvedIdStrategy.Kind.DETERMINISTIC_OBJECT_ID, "", 0);
                else if (kinds.equals(Set.of(TemplateFacts.IdKind.UUID))) strategy = new ResolvedIdStrategy(ResolvedIdStrategy.Kind.DETERMINISTIC_UUID, "", 0);
                else if (!kinds.isEmpty() && kinds.stream().allMatch(k -> k == TemplateFacts.IdKind.INT32 || k == TemplateFacts.IdKind.INT64)) {
                    long start = target.nextSequenceStart(collection.name(), "/_id", 1);
                    validateSequenceEnd(collection.name(), collection.count(), start, 1);
                    strategy = new ResolvedIdStrategy(ResolvedIdStrategy.Kind.NUMERIC_SEQUENCE, "", start);
                } else throw new ConfigurationException("Collection " + collection.name() + " requires an explicit /_id generation rule");
            }
            ids.put(collection.name(), strategy);
            collection.fields().forEach((path, rule) -> collectSequenceStarts(collection.name(), collection.count(), path, rule, sequenceStarts));
        }
    }
    private void collectSequenceStarts(String collection, long count, String path, GenerationRule rule, Map<String,Long> starts) {
        if (rule instanceof Sequence sequence && sequence.start() == SequenceStart.AUTO_AFTER_TARGET_MAX)
        {
            long start = target.nextSequenceStart(collection, path, sequence.step());
            validateSequenceEnd(collection, count, start, sequence.step());
            starts.put(collection + "\0" + path, start);
        }
        else if (rule instanceof ObjectValue object) object.fields().forEach((name,nested)->
                collectSequenceStarts(collection,count,path+"/"+name.replace("~","~0").replace("/","~1"),nested,starts));
    }
    private static void validateSequenceEnd(String collection,long count,long start,long step){
        if(count==0)return;try{Math.addExact(start,Math.multiplyExact(count-1,step));}
        catch(ArithmeticException e){throw new ConfigurationException("Sequence overflows BSON int64 in "+collection);}
    }

    private void resolveRandomStringIds(GenerationSpec spec, Map<String,ResolvedIdStrategy> ids,
                                        Map<String,RandomStringId> result) {
        for (CollectionGenerationSpec collection : spec.collections()) {
            ResolvedIdStrategy strategy = ids.get(collection.name());
            String path = strategy.kind() == ResolvedIdStrategy.Kind.EXPLICIT ? "/_id"
                    : strategy.kind() == ResolvedIdStrategy.Kind.FIELD_REFERENCE ? strategy.detail() : null;
            RandomStringId id = randomStringId(path, collection.fields(), new HashSet<>());
            if (id == null) continue;
            RandomString rule = id.rule();
            if (rule.minLength() != rule.maxLength()) continue;
            if (!required(rule)) continue;
            String alphabet = alphabet(rule);
            if (alphabet.chars().distinct().count() != alphabet.length()) continue;
            long required = Math.min(collection.count(), spec.batchSize());
            long capacity = 1;
            for (int i = 0; i < rule.minLength() && capacity < required; i++)
                capacity = capacity > required / alphabet.length() ? required : capacity * alphabet.length();
            if (capacity < required) continue;
            result.put(collection.name(), id);
        }
    }

    private RandomStringId randomStringId(String path, Map<String,GenerationRule> fields, Set<String> seen) {
        if (path == null || !seen.add(path)) return null;
        GenerationRule rule = fields.get(path);
        if (rule instanceof RandomString randomString) return new RandomStringId(path, randomString);
        if (rule instanceof Ref ref && ref.collection() == null) return randomStringId(ref.path(), fields, seen);
        if (path.equals("/_id") && rule instanceof Concat concat)
            return compositeRandomStringId(concat, fields);
        return null;
    }

    private RandomStringId compositeRandomStringId(Concat concat, Map<String,GenerationRule> fields) {
        int firstDynamic = -1;
        for (int i = 0; i < concat.parts().size(); i++) if (!(concat.parts().get(i) instanceof Literal)) {
            firstDynamic = i;
            break;
        }
        if (firstDynamic < 0) return null;
        RandomStringId id = randomStringComponent(concat.parts().get(firstDynamic), "/_id/part" + firstDynamic,
                fields, new HashSet<>());
        if (id == null) {
            return null;
        }
        if (!requiredIdTree(concat, fields, new HashSet<>())) return null;
        return id;
    }

    private RandomStringId randomStringComponent(GenerationRule rule, String evaluationPath,
                                                   Map<String,GenerationRule> fields, Set<String> seen) {
        if (rule instanceof RandomString randomString) return new RandomStringId(evaluationPath, randomString);
        if (!(rule instanceof Ref ref) || ref.collection() != null || ref.onMissing() != MissingPolicy.ERROR
                || !seen.add(ref.path())) return null;
        GenerationRule referenced = fields.get(ref.path());
        if (referenced == null) return null;
        return randomStringComponent(referenced, ref.path(), fields, seen);
    }

    private boolean requiredIdTree(GenerationRule rule, Map<String,GenerationRule> fields, Set<String> seenRefs) {
        if (!required(rule)) return false;
        if (rule instanceof Ref ref) {
            if (ref.collection() != null || ref.onMissing() != MissingPolicy.ERROR) return false;
            GenerationRule referenced = fields.get(ref.path());
            if (referenced != null && seenRefs.add(ref.path()))
                return requiredIdTree(referenced, fields, seenRefs);
        } else if (rule instanceof Concat nested) {
            return nested.parts().stream().allMatch(part -> requiredIdTree(part, fields, seenRefs));
        } else if (rule instanceof WeightedChoice choice) {
            return choice.choices().stream().allMatch(item -> requiredIdTree(item.value(), fields, seenRefs));
        }
        return true;
    }

    private static boolean required(GenerationRule rule) {
        return rule.options().nullProbability() == 0 && rule.options().omitProbability() == 0;
    }

    private static String alphabet(RandomString rule) { return switch (rule.alphabet()) {
        case UPPER_LATIN -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        case LOWER_LATIN -> "abcdefghijklmnopqrstuvwxyz";
        case ALPHANUMERIC -> "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        case HEX -> "0123456789abcdef";
        case CUSTOM -> rule.characters();
    }; }

    private void validateExplicitIdRule(CollectionGenerationSpec collection,
                                        Map<String,CollectionGenerationSpec> collections) {
        GenerationRule id = collection.fields().get("/_id");
        if (id != null) validateIdRule(collection.name(), id, collections, new HashSet<>());
    }

    private void validateIdRule(String owner, GenerationRule rule,
                                Map<String,CollectionGenerationSpec> collections, Set<String> seenRefs) {
        if (!required(rule))
            throw new ConfigurationException("Explicit _id rules cannot be null or omitted in " + owner);
        if (rule instanceof Array || rule instanceof ObjectValue)
            throw new ConfigurationException("Explicit _id rules must produce scalar BSON values in " + owner);
        if (rule instanceof Literal literal && !scalarLiteral(literal.value()))
            throw new ConfigurationException("Explicit _id literal values must be non-null scalars in " + owner);
        if (rule instanceof Ref ref) {
            if (ref.onMissing() != MissingPolicy.ERROR)
                throw new ConfigurationException("Explicit _id refs require onMissing: ERROR in " + owner);
            String targetCollection = ref.collection() == null ? owner : ref.collection();
            String key = targetCollection + "\0" + ref.path();
            CollectionGenerationSpec target = collections.get(targetCollection);
            GenerationRule referenced = ruleAtPath(target, ref.path());
            if (referenced != null && seenRefs.add(key))
                validateIdRule(targetCollection, referenced, collections, seenRefs);
        } else if (rule instanceof Concat concat) {
            for (GenerationRule part : concat.parts()) validateIdRule(owner, part, collections, seenRefs);
        } else if (rule instanceof WeightedChoice choice) {
            for (Choice item : choice.choices()) validateIdRule(owner, item.value(), collections, seenRefs);
        }
    }

    private static boolean scalarLiteral(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character;
    }

    private static GenerationRule ruleAtPath(CollectionGenerationSpec collection, String path) {
        if (collection == null) return null;
        GenerationRule exact = collection.fields().get(path);
        if (exact != null) return exact;
        String provider = collection.fields().keySet().stream()
                .filter(candidate -> path.startsWith(candidate + "/"))
                .max(Comparator.comparingInt(String::length)).orElse(null);
        if (provider == null) return null;
        GenerationRule current = collection.fields().get(provider);
        for (String token : path.substring(provider.length() + 1).split("/", -1)) {
            if (!(current instanceof ObjectValue object)) return null;
            current = object.fields().get(token.replace("~1", "/").replace("~0", "~"));
            if (current == null) return null;
        }
        return current;
    }

    void validateUniqueConstraints(GenerationSpec spec, Map<String,ResolvedIdStrategy> ids,
                                   Map<String,RandomStringId> randomStringIds,
                                   Map<String,IdRandomnessAnalyzer.Analysis> randomAnalyses,
                                   boolean allowUnprovenIds,
                                   Map<String,List<UniqueConstraint>> constraintsByCollection) {
        Map<String,Set<String>> proven = new LinkedHashMap<>();
        for (CollectionGenerationSpec collection : spec.collections()) {
            Set<String> uniquePaths = new LinkedHashSet<>();
            List<UniqueConstraint> constraints = target.uniqueConstraints(collection.name());
            constraintsByCollection.put(collection.name(), constraints);
            for (UniqueConstraint constraint : constraints) {
                if (constraint.partial() || constraint.sparse() || constraint.nonSimpleCollation())
                    throw new TargetPreparationException("Unique index " + constraint.name() + " is partial, sparse, or uses a non-simple collation and is unsupported in GENERATE v1");
                boolean provenSafe = constraint.paths().stream().anyMatch(path -> uniquePath(path, collection,
                        ids.get(collection.name()), Optional.ofNullable(randomStringIds.get(collection.name()))
                                .map(RandomStringId::path).orElse(null), proven));
                if (!provenSafe) {
                    boolean idFallback = constraint.name().equals("_id_")
                            && ids.get(collection.name()).kind() == ResolvedIdStrategy.Kind.EXPLICIT
                            && (randomAnalyses.getOrDefault(collection.name(), IdRandomnessAnalyzer.Analysis.none()).hasGuaranteedRandom()
                            || allowUnprovenIds && !idRandomness.effectivelyLiteral(collection));
                    if (!idFallback)
                        throw new TargetPreparationException("Cannot prove generated uniqueness for target index "
                                + collection.name() + "." + constraint.name());
                } else {
                    constraint.paths().stream().filter(path -> uniquePath(path, collection,
                            ids.get(collection.name()), Optional.ofNullable(randomStringIds.get(collection.name()))
                                    .map(RandomStringId::path).orElse(null), proven)).forEach(uniquePaths::add);
                }
            }
            proven.put(collection.name(), uniquePaths);
        }
    }
    private boolean uniquePath(String path, CollectionGenerationSpec collection, ResolvedIdStrategy id,
                               String batchUniqueRandomStringPath,
                               Map<String,Set<String>> proven) {
        if (batchUniqueRandomStringPath != null
                && (path.equals("/_id") || path.equals(batchUniqueRandomStringPath))) return true;
        if (path.equals("/_id") && id.kind() != ResolvedIdStrategy.Kind.EXPLICIT) {
            if (id.kind() != ResolvedIdStrategy.Kind.FIELD_REFERENCE) return true;
            GenerationRule referenced = collection.fields().get(id.detail());
            return referenced != null && uniqueRule(referenced, collection, batchUniqueRandomStringPath,
                    proven, new HashSet<>());
        }
        GenerationRule rule = collection.fields().get(path);
        return rule != null && uniqueRule(rule, collection, batchUniqueRandomStringPath, proven, new HashSet<>());
    }
    private boolean uniqueRule(GenerationRule rule, CollectionGenerationSpec collection,
                               String batchUniqueRandomStringPath, Map<String,Set<String>> proven,
                               Set<GenerationRule> seen) {
        if (!seen.add(rule) || rule.options().nullProbability() > 0 || rule.options().omitProbability() > 0) return false;
        if (rule instanceof Sequence || rule instanceof GenerationRule.ObjectId || rule instanceof Uuid) return true;
        if (rule instanceof Ref ref) {
            if (ref.collection() != null) return proven.getOrDefault(ref.collection(), Set.of()).contains(ref.path());
            if (batchUniqueRandomStringPath != null
                    && (ref.path().equals("/_id") || ref.path().equals(batchUniqueRandomStringPath))) return true;
            GenerationRule local = collection.fields().get(ref.path());
            return local != null && uniqueRule(local, collection, batchUniqueRandomStringPath, proven, seen);
        }
        if (rule instanceof Concat concat) {
            int unique = 0;
            for (GenerationRule part : concat.parts()) {
                if (uniqueRule(part, collection, batchUniqueRandomStringPath, proven, seen)) unique++;
                else if (!(part instanceof Literal)) return false;
            }
            return unique == 1;
        }
        return false;
    }

    void coverage(GenerationSpec spec, long seed, TemplateCatalog catalog,
                  Map<String,ResolvedIdStrategy> ids, Map<String,RandomStringId> randomStringIds,
                  Map<String,Long> starts, String generationId) {
        long iterations = spec.collections().stream().mapToLong(c -> catalog.count(c.name())).max().orElse(0);
        if (iterations == 0) return;
        Map<String,Map<String,GenerationRule>> coverageFields = new LinkedHashMap<>();
        for (CollectionGenerationSpec collection : spec.collections())
            coverageFields.put(collection.name(), coverageRules(collection.fields()));
        long chunkSize = Math.max(1, spec.batchSize());
        List<Callable<Long>> chunks = new ArrayList<>();
        for (long start = 0; start < iterations; start += chunkSize)
            chunks.add(coverageChunk(spec, seed, catalog, ids, randomStringIds, starts, coverageFields,
                    start, Math.min(start + chunkSize, iterations)));
        // Values are coordinate-derived, so chunk order cannot change them; futures are scanned in
        // chunk order so the reported failure is the same first failing iteration as a sequential run.
        ExecutorService executor = Executors.newFixedThreadPool(
                (int) Math.min(chunks.size(), Math.max(1, spec.parallelism())));
        try {
            AtomicLong completed = new AtomicLong();
            for (Future<Long> result : executor.invokeAll(chunks)) {
                try { completed.addAndGet(result.get()); }
                catch (ExecutionException e) { throw unwrap(e.getCause()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OperationCancelledException(); }
                progress.progress(generationId, GenerationOrchestrator.STAGES.get(8), completed.get(), iterations);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationCancelledException();
        } finally {
            executor.shutdownNow();
            try { executor.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private Callable<Long> coverageChunk(GenerationSpec spec, long seed, TemplateCatalog catalog,
                                         Map<String,ResolvedIdStrategy> ids, Map<String,RandomStringId> randomStringIds,
                                         Map<String,Long> starts,
                                         Map<String,Map<String,GenerationRule>> coverageFields, long start, long end) {
        return () -> {
            Map<String,BsonPayload> current = new LinkedHashMap<>();
            for (long iteration = start; iteration < end; iteration++) {
                checkCancelled();
                current.clear();
                for (CollectionGenerationSpec collection : spec.collections())
                    // Coverage must exercise the same batch-unique derivation the write phase uses:
                    // identical batchUniqueRandomStringPath and batch capacity per collection.
                    current.put(collection.name(), bson.generate(collection.name(), iteration, seed,
                            selectedTemplate(spec, seed, catalog, collection.name(), iteration),
                            coverageFields.get(collection.name()), ids.get(collection.name()), current, starts,
                            spec.sharedDates(),
                            Optional.ofNullable(randomStringIds.get(collection.name())).map(RandomStringId::path).orElse(null),
                            Math.toIntExact(Math.max(1, Math.min(collection.count(), spec.batchSize())))));
                for (BsonPayload payload : current.values())
                    if (payload.size() > spec.maxInFlightMegabytes() * 1024L * 1024L)
                        throw new GenerationException("Generated document for iteration " + iteration
                                + " (" + payload.size() + " bytes) exceeds maxInFlightMegabytes="
                                + spec.maxInFlightMegabytes());
            }
            return end - start;
        };
    }

    private Map<String,GenerationRule> coverageRules(Map<String,GenerationRule> fields) {
        LinkedHashMap<String,GenerationRule> result = new LinkedHashMap<>();
        fields.forEach((path,rule) -> result.put(path, coverageRule(rule)));
        return result;
    }
    private GenerationRule coverageRule(GenerationRule rule) {
        RuleOptions required = RuleOptions.REQUIRED;
        if (rule instanceof Literal v) return new Literal(v.value(), required);
        if (rule instanceof RandomString v) return new RandomString(v.alphabet(), v.characters(), v.minLength(), v.maxLength(), required);
        if (rule instanceof RandomAlphaNumStringBetween v)
            return new RandomAlphaNumStringBetween(v.min(), v.max(), v.length(), required);
        if (rule instanceof RandomNumber v) return new RandomNumber(v.bsonType(), v.min(), v.max(), required);
        if (rule instanceof WeightedChoice v) return new WeightedChoice(v.choices().stream()
                .map(choice -> new Choice(coverageRule(choice.value()), choice.weight())).toList(), required);
        if (rule instanceof RandomBoolean v) return new RandomBoolean(v.trueProbability(), required);
        if (rule instanceof Sequence v) return new Sequence(v.start(), v.explicitStart(), v.step(), required);
        if (rule instanceof GenerationRule.ObjectId) return new GenerationRule.ObjectId(required);
        if (rule instanceof Uuid v) return new Uuid(v.output(), required);
        if (rule instanceof DateTime v) return new DateTime(v.source(), v.output(), v.pattern(), v.zone(), v.locale(), required);
        if (rule instanceof Ref v) return new Ref(v.collection(), v.path(), v.onMissing(), required);
        if (rule instanceof Concat v) return new Concat(v.parts().stream().map(this::coverageRule).toList(), required);
        if (rule instanceof Array v) {
            int length = v.length().max() == 0 ? 0 : 1;
            return new Array(new LengthRange(length,length), coverageRule(v.items()), required);
        }
        ObjectValue object = (ObjectValue)rule; LinkedHashMap<String,GenerationRule> nested = new LinkedHashMap<>();
        object.fields().forEach((name,value) -> nested.put(name, coverageRule(value)));
        return new ObjectValue(nested, required);
    }

    // Template selection must stay identical to GenerationBatchExecutor.selectedTemplate: generated
    // values are coordinate-derived and coverage must observe the same templates as the write phase.
    private BsonPayload selectedTemplate(GenerationSpec spec, long seed, TemplateCatalog catalog,
                                         String collection, long iteration) {
        long count = catalog.count(collection);
        long ordinal = templateSelector.select(spec.templateSelection(), seed, collection, iteration, count);
        return catalog.get(collection, ordinal);
    }

    private void checkCancelled() { if (cancellation.isCancellationRequested()) throw new OperationCancelledException(); }
    private static RuntimeException unwrap(Throwable error) { return error instanceof RuntimeException runtime ? runtime : new GenerationException("Generation worker failed", error); }
    private static Set<String> intersection(Set<String> left, Set<String> right) { left.retainAll(right); return left; }

    record RandomStringId(String path, RandomString rule) { }
}
