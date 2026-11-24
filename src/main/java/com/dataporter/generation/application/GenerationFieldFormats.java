package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationFormatChange;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.ports.out.GenerationBsonEngine;
import com.dataporter.generation.ports.out.TemplateCatalog;
import com.dataporter.shared.error.ConfigurationException;
import com.dataporter.shared.error.OperationCancelledException;
import com.dataporter.shared.ports.out.CancellationToken;

import java.math.BigDecimal;
import java.util.*;

/** Compares the possible outputs of configured rules against the BSON types in the template snapshot. */
final class GenerationFieldFormats {
    private record RuleSite(String collection, String fieldPointer, String configPointer, GenerationRule rule) {}
    private record Reference(String collection, Ref rule) {}

    private final Map<String, Map<String, Set<String>>> observed = new LinkedHashMap<>();
    private final Map<String, ResolvedIdStrategy> referencedIds = new HashMap<>();

    GenerationFieldFormats(GenerationSpec spec, TemplateCatalog catalog, GenerationBsonEngine bson,
                           CancellationToken cancellation) {
        spec.collections().forEach(collection -> observed.put(collection.name(), new LinkedHashMap<>()));
        Set<String> implicitIds = new HashSet<>();
        for (CollectionGenerationSpec collection : spec.collections())
            if (!collection.fields().containsKey("/_id")) implicitIds.add(collection.name());
        for (RuleSite site : sites(spec)) {
            request(site.collection(), site.fieldPointer());
            if (site.rule() instanceof Ref ref) {
                request(referencedCollection(site.collection(), ref), ref.path());
                if (ref.collection() != null && ref.path().equals("/_id") && implicitIds.contains(ref.collection()))
                    referencedIds.computeIfAbsent(ref.collection(), name ->
                            GenerationPreflight.templateIdStrategy(name, catalog, bson, cancellation));
            }
        }
        observed.forEach((collection, types) -> {
            if (types.isEmpty()) return;
            for (long ordinal = 0, count = catalog.count(collection); ordinal < count; ordinal++) {
                if (cancellation.isCancellationRequested()) throw new OperationCancelledException();
                bson.collectFieldTypes(catalog.get(collection, ordinal), types);
            }
        });
    }

    List<GenerationFormatChange> corrections(GenerationSpec spec) {
        List<GenerationFormatChange> changes = new ArrayList<>();
        for (RuleSite site : sites(spec)) {
            Set<String> actual = observed(site.collection(), site.fieldPointer());
            if (actual.size() != 1) continue;
            Map<String, String> properties = correction(site.rule(), actual.iterator().next());
            if (!properties.isEmpty()) changes.add(new GenerationFormatChange(site.configPointer(), properties));
        }
        return changes;
    }

    void validate(GenerationSpec spec) {
        Map<String, CollectionGenerationSpec> collections = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> outputs = new LinkedHashMap<>();
        spec.collections().forEach(collection -> collections.put(collection.name(), collection));
        for (RuleSite site : sites(spec)) {
            if (observed(site.collection(), site.fieldPointer()).isEmpty() || site.rule() instanceof WeightedChoice) continue;
            Set<String> generated = outputTypes(site.rule(), site.collection(), "", collections, new HashSet<>());
            if (!generated.isEmpty()) {
                outputs.computeIfAbsent(site.collection(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(site.fieldPointer(), ignored -> new HashSet<>()).addAll(generated);
            }
        }
        outputs.forEach((collection, fields) -> fields.forEach((pointer, generated) -> {
            Set<String> actual = observed(collection, pointer);
            if (!actual.equals(generated))
                throw new ConfigurationException("BSON field format mismatch in " + collection
                        + pointer.replace("/~2", "/[]") + ": configured " + new TreeSet<>(generated)
                        + ", source templates " + new TreeSet<>(actual));
        }));
    }

    private Set<String> outputTypes(GenerationRule rule, String collection, String relative,
                                    Map<String, CollectionGenerationSpec> collections, Set<Reference> resolving) {
        if (!hasValueOutput(rule)) return Set.of();
        if (rule instanceof WeightedChoice choice) {
            Set<String> types = new HashSet<>();
            choice.choices().forEach(branch -> types.addAll(outputTypes(branch.value(), collection, relative, collections, resolving)));
            return types;
        }
        if (rule instanceof Ref ref) {
            String referenced = referencedCollection(collection, ref);
            String path = ref.path() + relative;
            Reference reference = new Reference(collection, ref);
            if (!resolving.add(reference)) return observed(referenced, path);
            try {
                ResolvedIdStrategy id = ref.collection() != null && path.equals("/_id")
                        ? referencedIds.get(referenced) : null;
                if (id != null) {
                    return switch (id.kind()) {
                        case DETERMINISTIC_OBJECT_ID -> Set.of("OBJECT_ID");
                        case DETERMINISTIC_UUID -> Set.of("UUID");
                        case NUMERIC_SEQUENCE -> Set.of("INT64");
                        case FIELD_REFERENCE -> configuredTypes(referenced, id.detail(), collections, resolving,
                                observed(referenced, path));
                        case EXPLICIT -> throw new IllegalStateException("Expected an inferred ID strategy");
                    };
                }
                return configuredTypes(referenced, path, collections, resolving, observed(referenced, path));
            } finally {
                resolving.remove(reference);
            }
        }
        if (relative.isEmpty()) return directTypes(rule);

        int end = relative.indexOf('/', 1);
        String segment = end < 0 ? relative.substring(1) : relative.substring(1, end);
        String rest = end < 0 ? "" : relative.substring(end);
        if (rule instanceof ObjectValue object) {
            GenerationRule child = object.fields().get(segment.replace("~1", "/").replace("~0", "~"));
            return child == null ? Set.of() : outputTypes(child, collection, rest, collections, resolving);
        }
        if (rule instanceof Array array && isArrayIndex(segment, array.length().max()))
            return outputTypes(array.items(), collection, rest, collections, resolving);
        if (rule instanceof Literal literal) {
            Object child = literalChild(literal.value(), segment);
            return child == null ? Set.of() : outputTypes(new Literal(child, RuleOptions.REQUIRED),
                    collection, rest, collections, resolving);
        }
        return Set.of();
    }

    private Set<String> configuredTypes(String collection, String path, Map<String, CollectionGenerationSpec> collections,
                                        Set<Reference> resolving, Set<String> fallback) {
        for (var provider : collections.get(collection).fields().entrySet()) {
            String pointer = provider.getKey();
            if (path.equals(pointer) || path.startsWith(pointer + "/"))
                return outputTypes(provider.getValue(), collection, path.substring(pointer.length()), collections, resolving);
        }
        return fallback;
    }

    private static Set<String> directTypes(GenerationRule rule) {
        return switch (rule) {
            case Literal literal -> literalTypes(literal.value());
            case RandomString ignored -> Set.of("STRING");
            case RandomAlphaNumStringBetween ignored -> Set.of("STRING");
            case RandomNumber number -> Set.of(number.bsonType().name());
            case Sequence ignored -> Set.of("INT64");
            case RandomBoolean ignored -> Set.of("BOOLEAN");
            case ObjectId ignored -> Set.of("OBJECT_ID");
            case Uuid uuid -> Set.of(uuid.output() == UuidOutput.STRING ? "STRING" : "UUID");
            case DateTime date -> Set.of(date.output() == DateOutput.STRING ? "STRING" : "DATE_TIME");
            case Concat ignored -> Set.of("STRING");
            case Array ignored -> Set.of("ARRAY");
            case ObjectValue ignored -> Set.of("DOCUMENT");
            case WeightedChoice ignored -> throw new IllegalStateException("Expected a weighted choice branch");
            case Ref ignored -> throw new IllegalStateException("Expected a resolved reference");
        };
    }

    private static Set<String> literalTypes(Object value) {
        return switch (value) {
            case null -> Set.of();
            case String ignored -> Set.of("STRING");
            case Boolean ignored -> Set.of("BOOLEAN");
            case Integer ignored -> Set.of("INT32");
            case Long ignored -> Set.of("INT64");
            case Double ignored -> Set.of("DOUBLE");
            case BigDecimal ignored -> Set.of("DECIMAL128");
            case List<?> ignored -> Set.of("ARRAY");
            case Map<?, ?> ignored -> Set.of("DOCUMENT");
            default -> throw new ConfigurationException("Unsupported generation literal type");
        };
    }

    private static Object literalChild(Object value, String segment) {
        if (value instanceof Map<?, ?> map) return map.get(segment.replace("~1", "/").replace("~0", "~"));
        if (value instanceof List<?> list && isArrayIndex(segment, list.size())) return list.get(Integer.parseInt(segment));
        return null;
    }

    private static boolean isArrayIndex(String segment, int max) {
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 && index < max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, String> correction(GenerationRule rule, String actual) {
        if (rule instanceof RandomNumber number) {
            try {
                NumberType target = NumberType.valueOf(actual);
                if (number.bsonType() != target) {
                    new RandomNumber(target, number.min(), number.max(), number.options());
                    return Map.of("bsonType", target.name());
                }
            } catch (IllegalArgumentException e) {
                // A different kind or a range that cannot be represented requires an explicit edit.
            }
        } else if (rule instanceof Uuid uuid) {
            if (actual.equals("STRING") && uuid.output() != UuidOutput.STRING)
                return Map.of("output", "STRING");
            if (actual.equals("UUID") && uuid.output() != UuidOutput.BSON_BINARY)
                return Map.of("output", "BSON_BINARY");
        } else if (rule instanceof DateTime date) {
            if (actual.equals("DATE_TIME") && date.output() != DateOutput.BSON_DATE)
                return Map.of("output", "BSON_DATE");
            if (actual.equals("STRING") && date.output() != DateOutput.STRING) {
                if (date.pattern() != null) return Map.of("output", "STRING");
                Map<String, String> properties = new LinkedHashMap<>();
                properties.put("output", "STRING");
                properties.put("pattern", "uuuu-MM-dd'T'HH:mm:ss.SSSXXX");
                return properties;
            }
        }
        return Map.of();
    }

    private void request(String collection, String fieldPointer) {
        observed.get(collection).computeIfAbsent(fieldPointer, ignored -> new HashSet<>());
    }

    private Set<String> observed(String collection, String fieldPointer) {
        return observed.get(collection).getOrDefault(fieldPointer, Set.of());
    }

    private static String referencedCollection(String collection, Ref ref) {
        return ref.collection() == null ? collection : ref.collection();
    }

    private static List<RuleSite> sites(GenerationSpec spec) {
        List<RuleSite> sites = new ArrayList<>();
        for (int index = 0; index < spec.collections().size(); index++) {
            CollectionGenerationSpec collection = spec.collections().get(index);
            String fields = "/collections/" + index + "/fields/";
            collection.fields().forEach((path, rule) -> collectSites(sites, collection.name(), path, fields + escape(path), rule));
        }
        return sites;
    }

    private static void collectSites(List<RuleSite> sites, String collection, String path, String config, GenerationRule rule) {
        if (!hasValueOutput(rule)) return;
        sites.add(new RuleSite(collection, path, config, rule));
        switch (rule) {
            case WeightedChoice choice -> {
                for (int index = 0; index < choice.choices().size(); index++)
                    collectSites(sites, collection, path, config + "/choices/" + index + "/value", choice.choices().get(index).value());
            }
            case ObjectValue object -> object.fields().forEach((name, child) ->
                    collectSites(sites, collection, path + "/" + escape(name), config + "/fields/" + escape(name), child));
            case Array array -> {
                if (array.length().max() > 0) collectSites(sites, collection, path + "/~2", config + "/items", array.items());
            }
            case Literal literal -> collectLiteralSites(sites, collection, path, config, literal.value());
            default -> { }
        }
    }

    private static void collectLiteralSites(List<RuleSite> sites, String collection, String path, String config, Object value) {
        if (value instanceof Map<?, ?> map)
            map.forEach((key, child) -> collectSites(sites, collection, path + "/" + escape((String) key),
                    config, new Literal(child, RuleOptions.REQUIRED)));
        if (value instanceof List<?> list)
            for (int index = 0; index < list.size(); index++)
                collectSites(sites, collection, path + "/" + index, config, new Literal(list.get(index), RuleOptions.REQUIRED));
    }

    private static boolean hasValueOutput(GenerationRule rule) {
        return rule.options().nullProbability() + rule.options().omitProbability() < 1;
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
