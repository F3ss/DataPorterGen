package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationRule.Concat;
import com.dataporter.generation.domain.GenerationRule.Literal;
import com.dataporter.generation.domain.GenerationRule.ObjectValue;
import com.dataporter.generation.domain.GenerationRule.RandomAlphaNumStringBetween;
import com.dataporter.generation.domain.GenerationRule.RandomString;
import com.dataporter.generation.domain.GenerationRule.Ref;
import com.dataporter.generation.domain.GenerationRule.WeightedChoice;

import java.math.BigInteger;
import java.util.*;

final class IdRandomnessAnalyzer {
    Analysis analyze(CollectionGenerationSpec collection,
                     Map<String, CollectionGenerationSpec> collections) {
        GenerationRule id = collection.fields().get("/_id");
        if (id == null) return Analysis.none();
        LinkedHashMap<String, RandomSource> sources = new LinkedHashMap<>();
        boolean weighted = collect(collection.name(), id, "/_id", collections, new HashSet<>(), sources);
        boolean guaranteed = guaranteesRandom(collection.name(), id, collections, new HashSet<>());
        return new Analysis(List.copyOf(sources.values()), guaranteed, weighted);
    }

    boolean effectivelyLiteral(CollectionGenerationSpec collection) {
        GenerationRule id = collection.fields().get("/_id");
        return id != null && effectivelyLiteral(id, collection, new HashSet<>());
    }

    private boolean collect(String owner, GenerationRule rule, String evaluationPath,
                            Map<String, CollectionGenerationSpec> collections,
                            Set<String> visitingRefs, Map<String, RandomSource> sources) {
        if (rule instanceof RandomString random) {
            String key = owner + "\0" + evaluationPath;
            sources.putIfAbsent(key, new RandomSource(key, random));
            return false;
        }
        if (rule instanceof RandomAlphaNumStringBetween random) {
            String key = owner + "\0" + evaluationPath;
            sources.putIfAbsent(key, new RandomSource(key, random));
            return false;
        }
        if (rule instanceof Concat concat) {
            boolean weighted = false;
            for (int i = 0; i < concat.parts().size(); i++)
                weighted |= collect(owner, concat.parts().get(i), evaluationPath + "/part" + i,
                        collections, visitingRefs, sources);
            return weighted;
        }
        if (rule instanceof WeightedChoice choice) {
            boolean nestedWeighted = true;
            for (int i = 0; i < choice.choices().size(); i++)
                nestedWeighted |= collect(owner, choice.choices().get(i).value(),
                        evaluationPath + "/choice" + i, collections, visitingRefs, sources);
            return nestedWeighted;
        }
        if (rule instanceof Ref ref) {
            String referencedCollection = ref.collection() == null ? owner : ref.collection();
            String key = referencedCollection + "\0" + ref.path();
            if (!visitingRefs.add(key)) return false;
            try {
                ResolvedRule resolved = resolve(collections.get(referencedCollection), ref.path());
                if (resolved != null)
                    return collect(referencedCollection, resolved.rule(), resolved.path(), collections, visitingRefs, sources);
            } finally {
                visitingRefs.remove(key);
            }
        }
        return false;
    }

    private boolean guaranteesRandom(String owner, GenerationRule rule,
                                     Map<String, CollectionGenerationSpec> collections,
                                     Set<String> visitingRefs) {
        if (rule instanceof RandomString || rule instanceof RandomAlphaNumStringBetween) return true;
        if (rule instanceof Concat concat)
            return concat.parts().stream().anyMatch(part ->
                    guaranteesRandom(owner, part, collections, visitingRefs));
        if (rule instanceof WeightedChoice choice)
            return choice.choices().stream().allMatch(item ->
                    guaranteesRandom(owner, item.value(), collections, visitingRefs));
        if (rule instanceof Ref ref) {
            String referencedCollection = ref.collection() == null ? owner : ref.collection();
            String key = referencedCollection + "\0" + ref.path();
            if (!visitingRefs.add(key)) return false;
            try {
                ResolvedRule resolved = resolve(collections.get(referencedCollection), ref.path());
                return resolved != null && guaranteesRandom(referencedCollection, resolved.rule(),
                        collections, visitingRefs);
            } finally {
                visitingRefs.remove(key);
            }
        }
        return false;
    }

    private boolean effectivelyLiteral(GenerationRule rule, CollectionGenerationSpec collection,
                                       Set<String> visitingRefs) {
        if (rule instanceof Literal) return true;
        if (rule instanceof Concat concat)
            return concat.parts().stream().allMatch(part -> effectivelyLiteral(part, collection, visitingRefs));
        if (rule instanceof Ref ref && ref.collection() == null && visitingRefs.add(ref.path())) {
            try {
                ResolvedRule resolved = resolve(collection, ref.path());
                return resolved != null && effectivelyLiteral(resolved.rule(), collection, visitingRefs);
            } finally {
                visitingRefs.remove(ref.path());
            }
        }
        return false;
    }

    private ResolvedRule resolve(CollectionGenerationSpec collection, String path) {
        if (collection == null) return null;
        GenerationRule exact = collection.fields().get(path);
        if (exact != null) return new ResolvedRule(path, exact);
        String provider = collection.fields().keySet().stream()
                .filter(candidate -> path.startsWith(candidate + "/"))
                .max(Comparator.comparingInt(String::length)).orElse(null);
        if (provider == null) return null;
        GenerationRule current = collection.fields().get(provider);
        String remaining = path.substring(provider.length() + 1);
        String resolvedPath = provider;
        for (String token : remaining.split("/", -1)) {
            if (!(current instanceof ObjectValue object)) return null;
            String name = token.replace("~1", "/").replace("~0", "~");
            current = object.fields().get(name);
            if (current == null) return null;
            resolvedPath += "/" + token;
        }
        return new ResolvedRule(resolvedPath, current);
    }

    record Analysis(List<RandomSource> sources, boolean hasGuaranteedRandom, boolean weightedDistribution) {
        static Analysis none() { return new Analysis(List.of(), false, false); }
        Analysis { sources = List.copyOf(sources); }
        boolean hasRandom() { return !sources.isEmpty(); }

        String warning(String collection, long count) {
            if (!hasRandom()) throw new IllegalStateException("random source is required");
            boolean unknown = weightedDistribution
                    || sources.stream().anyMatch(source -> !source.fixedLength());
            String keyspace;
            String risk;
            if (unknown) {
                keyspace = "unknown";
                risk = "unknown";
            } else {
                Space total = Space.one();
                for (RandomSource source : sources) total = total.multiply(source.space());
                keyspace = total.render();
                if (total.exact() != null && BigInteger.valueOf(count).compareTo(total.exact()) > 0) {
                    risk = "guaranteed";
                } else {
                    double probability = collisionProbability(count, total.logValue());
                    risk = formatProbability(probability);
                }
            }
            return "POSSIBLE _id COLLISIONS: collection=" + collection + " count=" + count
                    + " randomSources=" + sources.size() + " keyspace=" + keyspace + " risk=" + risk
                    + "; repeated _id values use exact-id upsert-replace and final collection growth may be less than count";
        }

        private static double collisionProbability(long count, double logSpace) {
            if (count < 2) return 0;
            double logPairs = Math.log((double) count) + Math.log((double) count - 1) - Math.log(2);
            double logLambda = logPairs - logSpace;
            if (logLambda > 40) return 1;
            if (logLambda < -745) return 0;
            return -Math.expm1(-Math.exp(logLambda));
        }

        private static String formatProbability(double probability) {
            if (probability == 0) return "0";
            if (probability >= .001) return String.format(Locale.ROOT, "%.2f%%", probability * 100);
            return String.format(Locale.ROOT, "%.3e", probability);
        }
    }

    record RandomSource(String key, GenerationRule rule) {
        boolean fixedLength() {
            return rule instanceof RandomAlphaNumStringBetween
                    || ((RandomString) rule).minLength() == ((RandomString) rule).maxLength();
        }
        Space space() {
            if (rule instanceof RandomAlphaNumStringBetween between)
                return Space.exact(between.max().subtract(between.min()));
            RandomString string = (RandomString) rule;
            long alphabetSize = alphabet(string).chars().distinct().count();
            return Space.power(alphabetSize, string.minLength());
        }
    }

    private record Space(BigInteger exact, double logValue) {
        static Space one() { return new Space(BigInteger.ONE, 0); }
        static Space exact(BigInteger value) { return new Space(value, Math.log(value.doubleValue())); }
        static Space power(long base, int exponent) {
            double log = exponent * Math.log(base);
            double digits = log / Math.log(10) + 1;
            BigInteger exact = digits <= 100 ? BigInteger.valueOf(base).pow(exponent) : null;
            return new Space(exact, log);
        }
        Space multiply(Space other) {
            BigInteger product = exact == null || other.exact == null ? null : exact.multiply(other.exact);
            if (product != null && product.toString().length() > 100) product = null;
            return new Space(product, logValue + other.logValue);
        }
        String render() {
            if (exact != null) return exact.toString();
            return String.format(Locale.ROOT, "~10^%.3f", logValue / Math.log(10));
        }
    }

    private record ResolvedRule(String path, GenerationRule rule) { }

    private static String alphabet(RandomString rule) {
        return switch (rule.alphabet()) {
            case UPPER_LATIN -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            case LOWER_LATIN -> "abcdefghijklmnopqrstuvwxyz";
            case ALPHANUMERIC -> "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            case HEX -> "0123456789abcdef";
            case CUSTOM -> rule.characters();
        };
    }
}
