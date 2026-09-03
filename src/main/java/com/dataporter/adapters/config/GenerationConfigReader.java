package com.dataporter.adapters.config;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.TemplateQuery;
import com.dataporter.generation.domain.TemplateSelection;
import com.dataporter.generation.ports.out.GenerationSpecReader;
import com.dataporter.shared.error.ConfigurationException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class GenerationConfigReader implements GenerationSpecReader {
    private final Path configPath;

    public GenerationConfigReader(Path configPath) { this.configPath = configPath; }

    @Override public GenerationSpec read() {
        try {
            byte[] content = Files.readAllBytes(configPath.toAbsolutePath().normalize());
            String lower = configPath.getFileName().toString().toLowerCase(Locale.ROOT);
            JsonFactory factory = lower.endsWith(".yaml") || lower.endsWith(".yml")
                    ? YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
                    : JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            ObjectMapper mapper = new ObjectMapper(factory);
            JsonNode root = mapper.readTree(content);
            requireObject(root, "root");
            unknown(root, "root", "version", "seed", "batchSize", "parallelism", "maxWorkingMegabytes",
                    "maxInFlightMegabytes", "templateSelection", "sharedDates", "collections");
            int version = requiredInt(root, "version", "root");
            Long seed = root.has("seed") ? requiredLong(root, "seed", "root") : null;
            TemplateSelection templateSelection = enumValue(TemplateSelection.class,
                    optionalText(root, "templateSelection", "SHUFFLED_CYCLE", "root"), "root.templateSelection");
            int batchSize = optionalInt(root, "batchSize", 1000, "root");
            int parallelism = optionalInt(root, "parallelism", 2, "root");
            long working = optionalLong(root, "maxWorkingMegabytes", 100, "root");
            long inFlight = optionalLong(root, "maxInFlightMegabytes", 256, "root");
            LinkedHashMap<String, SharedDateDefinition> sharedDates = new LinkedHashMap<>();
            if (root.has("sharedDates")) {
                JsonNode sharedDateNodes = root.get("sharedDates");
                requireObject(sharedDateNodes, "root.sharedDates");
                sharedDateNodes.properties().forEach(entry -> {
                    String at = "root.sharedDates." + entry.getKey();
                    DateSource source = parseDateSource(entry.getValue(), at);
                    if (!(source instanceof FixedDate) && !(source instanceof RandomDateRange))
                        fail(at + " supports only fixed or randomRange");
                    sharedDates.put(entry.getKey(), new SharedDateDefinition(source));
                });
            }
            JsonNode collectionNodes = required(root, "collections", "root");
            if (!collectionNodes.isArray()) fail("collections must be an array");
            List<CollectionGenerationSpec> collections = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < collectionNodes.size(); i++) {
                JsonNode node = collectionNodes.get(i);
                String at = "collections[" + i + "]";
                requireObject(node, at);
                unknown(node, at, "name", "count", "query", "fields");
                String name = requiredText(node, "name", at);
                if (!names.add(name)) fail("duplicate generation collection: " + name);
                long count = requiredLong(node, "count", at);
                TemplateQuery query = node.has("query")
                        ? parseTemplateQuery(node.get("query"), at + ".query")
                        : TemplateQuery.matchAll();
                JsonNode fieldNodes = required(node, "fields", at);
                requireObject(fieldNodes, at + ".fields");
                LinkedHashMap<String, GenerationRule> fields = new LinkedHashMap<>();
                fieldNodes.properties().forEach(entry -> fields.put(entry.getKey(), parseRule(entry.getValue(), at + ".fields." + entry.getKey())));
                collections.add(new CollectionGenerationSpec(name, count, query, fields));
            }
            return new GenerationSpec(version, seed, templateSelection, batchSize, parallelism, working, inFlight,
                    sharedDates, collections, sha256(content));
        } catch (ConfigurationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ConfigurationException("Cannot read generation config " + configPath + ": " + e.getMessage());
        }
    }

    private GenerationRule parseRule(JsonNode node, String at) {
        requireObject(node, at);
        String kind = requiredText(node, "kind", at);
        RuleOptions options = new RuleOptions(optionalDouble(node, "nullProbability", 0, at),
                optionalDouble(node, "omitProbability", 0, at));
        return switch (kind) {
            case "literal" -> {
                unknown(node, at, "kind", "value", "nullProbability", "omitProbability");
                if (!node.has("value")) fail(at + ".value is required");
                yield new Literal(javaValue(node.get("value")), options);
            }
            case "randomString" -> {
                unknown(node, at, "kind", "alphabet", "characters", "length", "minLength", "maxLength", "nullProbability", "omitProbability");
                Alphabet alphabet = enumValue(Alphabet.class, requiredText(node, "alphabet", at), at + ".alphabet");
                Integer fixed = node.has("length") ? requiredInt(node, "length", at) : null;
                int min = fixed == null ? requiredInt(node, "minLength", at) : fixed;
                int max = fixed == null ? requiredInt(node, "maxLength", at) : fixed;
                yield new RandomString(alphabet, optionalText(node, "characters", null, at), min, max, options);
            }
            case "randomAlphaNumStringBetween" -> {
                unknown(node, at, "kind", "min", "max", "length", "nullProbability", "omitProbability");
                yield new RandomAlphaNumStringBetween(requiredBigInteger(node, "min", at),
                        requiredBigInteger(node, "max", at), requiredInt(node, "length", at), options);
            }
            case "randomNumber" -> {
                unknown(node, at, "kind", "bsonType", "min", "max", "nullProbability", "omitProbability");
                yield new RandomNumber(enumValue(NumberType.class, requiredText(node, "bsonType", at), at + ".bsonType"),
                        requiredDecimal(node, "min", at), requiredDecimal(node, "max", at), options);
            }
            case "weightedChoice" -> {
                unknown(node, at, "kind", "choices", "nullProbability", "omitProbability");
                JsonNode choices = required(node, "choices", at);
                if (!choices.isArray()) fail(at + ".choices must be an array");
                List<Choice> values = new ArrayList<>();
                for (int i = 0; i < choices.size(); i++) {
                    JsonNode choice = choices.get(i);
                    String choiceAt = at + ".choices[" + i + "]";
                    requireObject(choice, choiceAt);
                    unknown(choice, choiceAt, "value", "weight");
                    JsonNode value = required(choice, "value", choiceAt);
                    GenerationRule valueRule = value.isObject() && value.has("kind")
                            ? parseRule(value, choiceAt + ".value")
                            : new Literal(javaValue(value), RuleOptions.REQUIRED);
                    values.add(new Choice(valueRule, requiredDouble(choice, "weight", choiceAt)));
                }
                yield new WeightedChoice(values, options);
            }
            case "randomBoolean" -> {
                unknown(node, at, "kind", "trueProbability", "nullProbability", "omitProbability");
                yield new RandomBoolean(optionalDouble(node, "trueProbability", 0.5, at), options);
            }
            case "sequence" -> {
                unknown(node, at, "kind", "start", "step", "nullProbability", "omitProbability");
                JsonNode start = required(node, "start", at);
                if (start.isTextual() && start.textValue().equals("AUTO_AFTER_TARGET_MAX"))
                    yield new Sequence(SequenceStart.AUTO_AFTER_TARGET_MAX, 0, optionalLong(node, "step", 1, at), options);
                if (!start.canConvertToLong()) fail(at + ".start must be an integer or AUTO_AFTER_TARGET_MAX");
                yield new Sequence(SequenceStart.EXPLICIT, start.longValue(), optionalLong(node, "step", 1, at), options);
            }
            case "objectId" -> {
                unknown(node, at, "kind", "nullProbability", "omitProbability");
                yield new ObjectId(options);
            }
            case "uuid" -> {
                unknown(node, at, "kind", "output", "nullProbability", "omitProbability");
                yield new Uuid(enumValue(UuidOutput.class, optionalText(node, "output", "BSON_BINARY", at), at + ".output"), options);
            }
            case "dateTime" -> {
                unknown(node, at, "kind", "source", "output", "pattern", "zone", "locale", "nullProbability", "omitProbability");
                DateOutput output = enumValue(DateOutput.class, requiredText(node, "output", at), at + ".output");
                yield new DateTime(parseDateSource(required(node, "source", at), at + ".source"), output,
                        optionalText(node, "pattern", null, at), optionalText(node, "zone", "UTC", at),
                        optionalText(node, "locale", "ROOT", at), options);
            }
            case "ref" -> {
                unknown(node, at, "kind", "collection", "path", "onMissing", "nullProbability", "omitProbability");
                yield new Ref(optionalText(node, "collection", null, at), requiredText(node, "path", at),
                        enumValue(MissingPolicy.class, optionalText(node, "onMissing", "ERROR", at), at + ".onMissing"), options);
            }
            case "concat" -> {
                unknown(node, at, "kind", "parts", "nullProbability", "omitProbability");
                JsonNode parts = required(node, "parts", at);
                if (!parts.isArray()) fail(at + ".parts must be an array");
                List<GenerationRule> rules = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) rules.add(parseRule(parts.get(i), at + ".parts[" + i + "]"));
                yield new Concat(rules, options);
            }
            case "array" -> {
                unknown(node, at, "kind", "length", "items", "nullProbability", "omitProbability");
                JsonNode length = required(node, "length", at);
                LengthRange range;
                if (length.canConvertToInt()) range = new LengthRange(length.intValue(), length.intValue());
                else {
                    requireObject(length, at + ".length");
                    unknown(length, at + ".length", "min", "max");
                    range = new LengthRange(requiredInt(length, "min", at), requiredInt(length, "max", at));
                }
                yield new Array(range, parseRule(required(node, "items", at), at + ".items"), options);
            }
            case "object" -> {
                unknown(node, at, "kind", "fields", "nullProbability", "omitProbability");
                JsonNode fields = required(node, "fields", at);
                requireObject(fields, at + ".fields");
                LinkedHashMap<String, GenerationRule> rules = new LinkedHashMap<>();
                fields.properties().forEach(e -> rules.put(e.getKey(), parseRule(e.getValue(), at + ".fields." + e.getKey())));
                yield new ObjectValue(rules, options);
            }
            default -> throw new ConfigurationException("Unsupported generation rule kind at " + at + ": " + kind);
        };
    }

    private DateSource parseDateSource(JsonNode node, String at) {
        requireObject(node, at);
        String kind = requiredText(node, "kind", at);
        return switch (kind) {
            case "randomRange" -> {
                unknown(node, at, "kind", "from", "to");
                yield new RandomDateRange(instant(requiredText(node, "from", at), at), instant(requiredText(node, "to", at), at));
            }
            case "fixed" -> {
                unknown(node, at, "kind", "value");
                yield new FixedDate(instant(requiredText(node, "value", at), at));
            }
            case "ref" -> {
                unknown(node, at, "kind", "collection", "path");
                yield new DateRef(optionalText(node, "collection", null, at), requiredText(node, "path", at));
            }
            case "shared" -> {
                unknown(node, at, "kind", "name");
                yield new SharedDateRef(requiredText(node, "name", at));
            }
            default -> throw new ConfigurationException("Unsupported date source kind at " + at + ": " + kind);
        };
    }

    private static TemplateQuery parseTemplateQuery(JsonNode node, String at) {
        requireObject(node, at);
        LinkedHashMap<String, Object> document = new LinkedHashMap<>();
        node.properties().forEach(entry -> document.put(entry.getKey(), queryValue(entry.getValue())));
        return new TemplateQuery(document);
    }

    private static Object queryValue(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) {
            if (node.canConvertToInt()) return node.intValue();
            if (node.canConvertToLong()) return node.longValue();
            throw new ConfigurationException("Integer query value is outside BSON int64 range");
        }
        if (node.isFloatingPointNumber()) return node.decimalValue();
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(value -> result.add(queryValue(value)));
            return Collections.unmodifiableList(result);
        }
        if (node.isObject()) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), queryValue(entry.getValue())));
            return Collections.unmodifiableMap(result);
        }
        throw new ConfigurationException("Unsupported generation query value");
    }

    private static Object javaValue(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) {
            if (node.canConvertToInt()) return node.intValue();
            if (node.canConvertToLong()) return node.longValue();
            throw new ConfigurationException("Integer literal is outside BSON int64 range");
        }
        if (node.isFloatingPointNumber()) return node.decimalValue();
        if (node.isArray()) { List<Object> result = new ArrayList<>(); node.forEach(v -> result.add(javaValue(v))); return List.copyOf(result); }
        if (node.isObject()) { LinkedHashMap<String,Object> result = new LinkedHashMap<>(); node.properties().forEach(e -> result.put(e.getKey(), javaValue(e.getValue()))); return Collections.unmodifiableMap(result); }
        throw new ConfigurationException("Unsupported literal value");
    }

    private static void unknown(JsonNode node, String at, String... allowed) {
        Set<String> names = Set.of(allowed);
        node.properties().forEach(entry -> { if (!names.contains(entry.getKey())) fail("Unknown property " + at + "." + entry.getKey()); });
    }
    private static JsonNode required(JsonNode node, String name, String at) {
        JsonNode value = node.get(name); if (value == null) fail(at + "." + name + " is required"); return value;
    }
    private static String requiredText(JsonNode n, String k, String at) { JsonNode v=required(n,k,at); if(!v.isTextual()||v.textValue().isBlank()) fail(at+"."+k+" must be text"); return v.textValue(); }
    private static String optionalText(JsonNode n,String k,String d,String at){if(!n.has(k))return d;return requiredText(n,k,at);}
    private static int requiredInt(JsonNode n,String k,String at){JsonNode v=required(n,k,at);if(!v.canConvertToInt())fail(at+"."+k+" must be an integer");return v.intValue();}
    private static int optionalInt(JsonNode n,String k,int d,String at){return n.has(k)?requiredInt(n,k,at):d;}
    private static long requiredLong(JsonNode n,String k,String at){JsonNode v=required(n,k,at);if(!v.canConvertToLong())fail(at+"."+k+" must be an integer");return v.longValue();}
    private static long optionalLong(JsonNode n,String k,long d,String at){return n.has(k)?requiredLong(n,k,at):d;}
    private static double requiredDouble(JsonNode n,String k,String at){JsonNode v=required(n,k,at);if(!v.isNumber())fail(at+"."+k+" must be a number");return v.doubleValue();}
    private static double optionalDouble(JsonNode n,String k,double d,String at){return n.has(k)?requiredDouble(n,k,at):d;}
    private static BigDecimal requiredDecimal(JsonNode n,String k,String at){JsonNode v=required(n,k,at);if(!v.isNumber())fail(at+"."+k+" must be a number");return v.decimalValue();}
    private static BigInteger requiredBigInteger(JsonNode n,String k,String at){JsonNode v=required(n,k,at);if(!v.isIntegralNumber())fail(at+"."+k+" must be an integer");return v.bigIntegerValue();}
    private static void requireObject(JsonNode node,String at){if(node==null||!node.isObject())fail(at+" must be an object");}
    private static <E extends Enum<E>> E enumValue(Class<E> type,String value,String at){try{return Enum.valueOf(type,value);}catch(RuntimeException e){throw new ConfigurationException(at+" has unsupported value "+value);}}
    private static Instant instant(String value,String at){try{return Instant.parse(value);}catch(RuntimeException e){throw new ConfigurationException(at+" must be an ISO-8601 instant");}}
    private static String sha256(byte[] bytes){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);return HexFormat.of().formatHex(digest);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void fail(String message){throw new ConfigurationException(message);}
}
