package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.error.GenerationException;
import com.dataporter.shared.bson.BsonPayload;

import org.bson.*;
import org.bson.types.Decimal128;

import java.math.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Evaluates generation rules into BSON values; all randomness is derived from (seed, collection, iteration, path). */
final class BsonRuleEvaluator {
    static final Object OMIT = new Object();
    // One digest per worker thread: instantiation dominated per-value randomness and is not thread-safe to share.
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    });
    // The same fields map instance is passed for every iteration of a collection; order depends only on it.
    private final Map<Map<String, GenerationRule>, List<String>> evaluationOrderCache =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final BsonPointerOperations paths;

    BsonRuleEvaluator(BsonPointerOperations paths) { this.paths = paths; }

    Object evaluate(GenerationRule rule, String collection, long iteration, long seed, String path,
                    BsonDocument current, Map<String, BsonPayload> other, Map<String, Long> starts,
                    Map<String, SharedDateDefinition> sharedDates,
                    String batchUniqueRandomStringPath, int batchSize) {
        return evaluate(rule, collection, iteration, seed, path, path, current, other, starts,
                sharedDates, batchUniqueRandomStringPath, batchSize, false);
    }

    private Object evaluate(GenerationRule rule, String collection, long iteration, long seed, String path,
                            String coordinate, BsonDocument current, Map<String, BsonPayload> other,
                            Map<String, Long> starts, Map<String, SharedDateDefinition> sharedDates,
                            String batchUniqueRandomStringPath, int batchSize,
                            boolean compositeIdComponent) {
        double gate = unit(seed, collection, iteration, coordinate + "#presence");
        if (gate < rule.options().omitProbability()) {
            if (compositeIdComponent) throw invalidCompositeIdComponent(path);
            return OMIT;
        }
        if (gate < rule.options().omitProbability() + rule.options().nullProbability()) {
            if (compositeIdComponent) throw invalidCompositeIdComponent(path);
            return BsonNull.VALUE;
        }
        if (rule instanceof Literal literal) return bson(literal.value());
        if (rule instanceof RandomString string) {
            String alphabet = alphabet(string);
            int length = betweenInt(seed, collection, iteration, coordinate + "#length", string.minLength(), string.maxLength());
            if (path.equals(batchUniqueRandomStringPath))
                return new BsonString(batchUniqueRandomString(seed, collection, iteration, coordinate, alphabet, length, batchSize));
            StringBuilder value = new StringBuilder(length);
            for (int i = 0; i < length; i++) value.append(alphabet.charAt(betweenInt(seed, collection, iteration, coordinate + "#" + i, 0, alphabet.length() - 1)));
            return new BsonString(value.toString());
        }
        if (rule instanceof RandomAlphaNumStringBetween string) {
            long span = string.max().subtract(string.min()).longValueExact();
            long offset = boundedLong(hash(seed, collection, iteration, coordinate), 0, span - 1);
            String encoded = string.min().add(BigInteger.valueOf(offset)).toString(36).toUpperCase(Locale.ROOT);
            return new BsonString("0".repeat(string.length() - encoded.length()) + encoded);
        }
        if (rule instanceof RandomNumber number) return randomNumber(number, seed, collection, iteration, coordinate);
        if (rule instanceof WeightedChoice choice) {
            GenerationRule selected = choice.select(unit(seed, collection, iteration, coordinate + "#choice"));
            return evaluate(selected, collection, iteration, seed, path, coordinate + "#selected",
                    current, other, starts, sharedDates,
                    batchUniqueRandomStringPath, batchSize, compositeIdComponent);
        }
        if (rule instanceof RandomBoolean bool) return BsonBoolean.valueOf(unit(seed, collection, iteration, coordinate) < bool.trueProbability());
        if (rule instanceof Sequence sequence) {
            long start = sequence.start() == SequenceStart.EXPLICIT ? sequence.explicitStart() : requiredStart(starts, collection, path);
            return new BsonInt64(Math.addExact(start, Math.multiplyExact(iteration, sequence.step())));
        }
        if (rule instanceof GenerationRule.ObjectId) return new BsonObjectId(new org.bson.types.ObjectId(Arrays.copyOf(hash(seed, collection, iteration, coordinate), 12)));
        if (rule instanceof Uuid uuid) {
            UUID value = uuid(seed, collection, iteration, coordinate);
            return uuid.output() == UuidOutput.STRING ? new BsonString(value.toString()) : new BsonBinary(value);
        }
        if (rule instanceof Ref ref) return reference(ref.collection(), ref.path(), ref.onMissing(), current, other);
        if (rule instanceof Concat concat) {
            StringBuilder value = new StringBuilder();
            int i = 0;
            boolean compositeId = compositeIdComponent || path.equals("/_id");
            for (GenerationRule part : concat.parts()) {
                String partPath = path + "/part" + i;
                String partCoordinate = coordinate + "/part" + i++;
                Object result = evaluate(part, collection, iteration, seed, partPath, partCoordinate,
                        current, other, starts, sharedDates,
                        batchUniqueRandomStringPath, batchSize, compositeId);
                if (result == OMIT) return OMIT;
                BsonValue component = (BsonValue) result;
                if (compositeId && (component.isNull() || component.isArray() || component.isDocument()))
                    throw invalidCompositeIdComponent(path);
                value.append(scalarString(component));
            }
            return new BsonString(value.toString());
        }
        if (rule instanceof DateTime date) {
            Instant instant = dateInstant(date.source(), seed, collection, iteration, coordinate, current, other,
                    sharedDates);
            if (date.output() == DateOutput.BSON_DATE) return new BsonDateTime(instant.toEpochMilli());
            Locale locale = "ROOT".equals(date.locale()) ? Locale.ROOT : new Locale.Builder().setLanguageTag(date.locale()).build();
            return new BsonString(DateTimeFormatter.ofPattern(date.pattern(), locale).withZone(ZoneId.of(date.zone())).format(instant));
        }
        if (rule instanceof Array array) {
            int length = betweenInt(seed, collection, iteration, coordinate + "#length",
                    array.length().min(), array.length().max());
            BsonArray values = new BsonArray();
            for (int i = 0; i < length; i++) {
                Object value = evaluate(array.items(), collection, iteration, seed, path + "/" + i,
                        coordinate + "/" + i, current, other, starts, sharedDates,
                        batchUniqueRandomStringPath, batchSize, false);
                if (value != OMIT) values.add((BsonValue) value);
            }
            return values;
        }
        if (rule instanceof ObjectValue object) {
            BsonDocument value = new BsonDocument();
            LinkedHashMap<String,GenerationRule> absolute = new LinkedHashMap<>();
            object.fields().forEach((name,nested) -> absolute.put(path + "/" + BsonPointerOperations.escape(name), nested));
            for (String nestedPath : evaluationOrder(absolute)) {
                String relative = nestedPath.substring(path.length());
                Object evaluated = evaluate(absolute.get(nestedPath), collection, iteration, seed, nestedPath,
                        coordinate + relative, current, other, starts, sharedDates,
                        batchUniqueRandomStringPath, batchSize, false);
                String name = paths.tokens(nestedPath).getLast();
                if (evaluated == OMIT) paths.remove(current, nestedPath);
                else { value.put(name, (BsonValue)evaluated); paths.set(current, nestedPath, (BsonValue)evaluated); }
            }
            return value;
        }
        throw new GenerationException("Unsupported rule at " + path);
    }

    void applyResolvedId(BsonDocument document, String collection, long iteration, long seed,
                         ResolvedIdStrategy strategy, Map<String, Long> starts) {
        switch (strategy.kind()) {
            case EXPLICIT -> { }
            case FIELD_REFERENCE -> {
                BsonValue value = paths.get(document, strategy.detail());
                if (value == null) throw new GenerationException("Resolved _id reference is missing: " + strategy.detail());
                paths.set(document, "/_id", value);
            }
            case DETERMINISTIC_OBJECT_ID -> paths.set(document, "/_id", new BsonObjectId(new org.bson.types.ObjectId(Arrays.copyOf(hash(seed, collection, iteration, "/_id"), 12))));
            case DETERMINISTIC_UUID -> paths.set(document, "/_id", new BsonBinary(uuid(seed, collection, iteration, "/_id")));
            case NUMERIC_SEQUENCE -> paths.set(document, "/_id", new BsonInt64(Math.addExact(strategy.numericStart(), iteration)));
        }
    }

    private Object reference(String collection, String path, MissingPolicy policy, BsonDocument current,
                             Map<String, BsonPayload> other) {
        BsonDocument source = current;
        if (collection != null) {
            BsonPayload payload = other.get(collection);
            if (payload == null) return missing(policy, collection + path);
            source = MongoBson.decode(payload);
        }
        BsonValue value = paths.get(source, path);
        return value == null ? missing(policy, path) : value;
    }
    private Object missing(MissingPolicy policy, String path) {
        return switch (policy) {
            case ERROR -> throw new GenerationException("Missing referenced value " + path);
            case NULL -> BsonNull.VALUE;
            case OMIT -> OMIT;
        };
    }

    private Instant dateInstant(DateSource source, long seed, String collection, long iteration, String path,
                                BsonDocument current, Map<String, BsonPayload> other,
                                Map<String, SharedDateDefinition> sharedDates) {
        if (source instanceof FixedDate fixed) return fixed.value();
        if (source instanceof RandomDateRange range) {
            long from = range.from().toEpochMilli(), to = range.to().toEpochMilli();
            if (from == to) return range.from();
            long millis = boundedLong(hash(seed, collection, iteration, path + "#date"), from, to);
            return Instant.ofEpochMilli(millis);
        }
        if (source instanceof SharedDateRef ref)
            return sharedDateInstant(ref.name(), seed, iteration, sharedDates);
        DateRef ref = (DateRef) source;
        Object value = reference(ref.collection(), ref.path(), MissingPolicy.ERROR, current, other);
        BsonValue bson = (BsonValue) value;
        if (bson.isDateTime()) return Instant.ofEpochMilli(bson.asDateTime().getValue());
        if (bson.isString()) return Instant.parse(bson.asString().getValue());
        throw new GenerationException("dateTime ref is not a BSON Date or ISO instant string: " + ref.path());
    }

    private Instant sharedDateInstant(String name, long seed, long iteration,
                                      Map<String, SharedDateDefinition> sharedDates) {
        SharedDateDefinition definition = sharedDates.get(name);
        if (definition == null) throw new GenerationException("Unknown shared date " + name);
        if (definition.source() instanceof FixedDate fixed)
            return Instant.ofEpochMilli(fixed.value().toEpochMilli());
        RandomDateRange range = (RandomDateRange) definition.source();
        long from = range.from().toEpochMilli(), to = range.to().toEpochMilli();
        if (from == to) return Instant.ofEpochMilli(from);
        long millis = boundedLong(hash(seed, "$sharedDate", iteration, name), from, to);
        return Instant.ofEpochMilli(millis);
    }

    private BsonValue randomNumber(RandomNumber number, long seed, String collection, long iteration, String path) {
        return switch (number.bsonType()) {
            case INT32 -> new BsonInt32((int) boundedLong(hash(seed, collection, iteration, path), number.min().longValueExact(), number.max().longValueExact()));
            case INT64 -> new BsonInt64(boundedLong(hash(seed, collection, iteration, path), number.min().longValueExact(), number.max().longValueExact()));
            case DOUBLE -> new BsonDouble(number.min().doubleValue() + unit(seed, collection, iteration, path) * number.max().subtract(number.min()).doubleValue());
            case DECIMAL128 -> {
                BigDecimal fraction = BigDecimal.valueOf(unit(seed, collection, iteration, path));
                yield new BsonDecimal128(new Decimal128(number.min().add(number.max().subtract(number.min()).multiply(fraction))));
            }
        };
    }

    List<String> cachedEvaluationOrder(Map<String, GenerationRule> fields) {
        List<String> cached = evaluationOrderCache.get(fields);
        if (cached != null) return cached;
        List<String> computed = evaluationOrder(fields);
        evaluationOrderCache.put(fields, computed);
        return computed;
    }

    private List<String> evaluationOrder(Map<String, GenerationRule> fields) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        fields.forEach((path, rule) -> {
            Set<String> refs = new LinkedHashSet<>(), providers = new LinkedHashSet<>(); collectLocalRefs(rule, refs);
            refs.forEach(ref -> { String provider = provider(ref, fields.keySet()); if (provider != null) providers.add(provider); });
            providers.remove(path);
            dependencies.put(path, providers);
        });
        List<String> result = new ArrayList<>(); Set<String> visiting = new HashSet<>(), done = new HashSet<>();
        for (String path : fields.keySet()) order(path, dependencies, visiting, done, result);
        return result;
    }
    private void order(String path, Map<String,Set<String>> graph, Set<String> visiting, Set<String> done, List<String> result) {
        if (done.contains(path)) return;
        if (!visiting.add(path)) throw new GenerationException("Cyclic field dependency at " + path);
        graph.getOrDefault(path, Set.of()).forEach(dependency -> order(dependency, graph, visiting, done, result));
        visiting.remove(path); done.add(path); result.add(path);
    }
    private void collectLocalRefs(GenerationRule rule, Set<String> refs) {
        if (rule instanceof Ref ref && ref.collection() == null) refs.add(ref.path());
        else if (rule instanceof DateTime date && date.source() instanceof DateRef ref && ref.collection() == null) refs.add(ref.path());
        else if (rule instanceof Concat concat) concat.parts().forEach(part -> collectLocalRefs(part, refs));
        else if (rule instanceof WeightedChoice choice)
            choice.choices().forEach(item -> collectLocalRefs(item.value(), refs));
        else if (rule instanceof Array array) collectLocalRefs(array.items(), refs);
        else if (rule instanceof ObjectValue object) object.fields().values().forEach(value -> collectLocalRefs(value, refs));
    }
    private static String provider(String ref, Set<String> fields) {
        return fields.stream().filter(path -> path.equals(ref) || ancestor(path, ref) || ancestor(ref, path))
                .max(Comparator.comparingInt(String::length)).orElse(null);
    }
    private static boolean ancestor(String parent,String child){return !parent.equals(child)&&child.startsWith(parent.endsWith("/")?parent:parent+"/");}

    @SuppressWarnings("unchecked") private static BsonValue bson(Object value) {
        if (value == null) return BsonNull.VALUE;
        if (value instanceof String v) return new BsonString(v);
        if (value instanceof Boolean v) return BsonBoolean.valueOf(v);
        if (value instanceof Integer v) return new BsonInt32(v);
        if (value instanceof Long v) return new BsonInt64(v);
        if (value instanceof BigDecimal v) return new BsonDecimal128(new Decimal128(v));
        if (value instanceof Double v) return new BsonDouble(v);
        if (value instanceof List<?> list) { BsonArray array = new BsonArray(); list.forEach(item -> array.add(bson(item))); return array; }
        if (value instanceof Map<?,?> map) { BsonDocument document = new BsonDocument(); map.forEach((k,v) -> document.put(String.valueOf(k), bson(v))); return document; }
        throw new GenerationException("Unsupported literal value type " + value.getClass().getSimpleName());
    }
    private static String scalarString(BsonValue value) {
        if (value == null || value.isNull()) return "null";
        if (value.isString()) return value.asString().getValue();
        if (value.isBoolean()) return Boolean.toString(value.asBoolean().getValue());
        if (value.isInt32()) return Integer.toString(value.asInt32().getValue());
        if (value.isInt64()) return Long.toString(value.asInt64().getValue());
        if (value.isDouble()) return Double.toString(value.asDouble().getValue());
        if (value.isDecimal128()) return value.asDecimal128().getValue().bigDecimalValue().toPlainString();
        if (value.isObjectId()) return value.asObjectId().getValue().toHexString();
        throw new GenerationException("concat supports scalar values only");
    }
    private static GenerationException invalidCompositeIdComponent(String path) {
        return new GenerationException("Composite /_id component is missing, null, object, or array at " + path);
    }
    private static String alphabet(RandomString rule) { return switch(rule.alphabet()) {
        case UPPER_LATIN -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"; case LOWER_LATIN -> "abcdefghijklmnopqrstuvwxyz";
        case ALPHANUMERIC -> ALPHANUMERIC;
        case HEX -> "0123456789abcdef"; case CUSTOM -> rule.characters();
    }; }
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L,
            100_000_000L, 1_000_000_000L, 10_000_000_000L, 100_000_000_000L, 1_000_000_000_000L,
            10_000_000_000_000L, 100_000_000_000_000L, 1_000_000_000_000_000L, 10_000_000_000_000_000L,
            100_000_000_000_000_000L, 1_000_000_000_000_000_000L};

    /** RANDOM unconfigured-fields mode: replaces every unkept value in place with a deterministic
     * random of the same shape as the template value — strings keep their length, numbers keep
     * their digit count (plus decimals count for double/decimal), arrays keep their length with
     * each element reshaped, documents recurse. Kept paths keep real template values, mirroring retain. */
    BsonDocument randomize(BsonDocument document, Set<String> keep, String collection, long iteration, long seed) {
        randomizeDocument(document, keep, "", collection, iteration, seed);
        return document;
    }
    private void randomizeDocument(BsonDocument document, Set<String> keep, String prefix,
                                   String collection, long iteration, long seed) {
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            String path = prefix + "/" + BsonPointerOperations.escape(entry.getKey());
            BsonValue value = entry.getValue();
            if (value.isDocument() && !keep.contains(path))
                randomizeDocument(value.asDocument(), keep, path, collection, iteration, seed);
            else if (BsonPointerOperations.kept(path, keep)) continue;
            else if (value.isArray()) randomizeArray(value.asArray(), keep, path, collection, iteration, seed);
            else entry.setValue(randomValue(value, path, collection, iteration, seed));
        }
    }
    private void randomizeArray(BsonArray array, Set<String> keep, String path,
                                String collection, long iteration, long seed) {
        for (int i = 0; i < array.size(); i++)
            if (BsonPointerOperations.kept(path + "/" + i, keep)) return; // whole array keeps real values, mirroring retain
        for (int i = 0; i < array.size(); i++) {
            String elementPath = path + "/" + i;
            BsonValue element = array.get(i);
            if (element.isDocument()) randomizeDocument(element.asDocument(), keep, elementPath, collection, iteration, seed);
            else if (element.isArray()) randomizeArray(element.asArray(), keep, elementPath, collection, iteration, seed);
            else array.set(i, randomValue(element, elementPath, collection, iteration, seed));
        }
    }
    private BsonValue randomValue(BsonValue value, String path, String collection, long iteration, long seed) {
        if (value.isString()) return new BsonString(randomText(value.asString().getValue().length(), collection, iteration, seed, path));
        if (value.isSymbol()) return new BsonSymbol(randomText(value.asSymbol().getSymbol().length(), collection, iteration, seed, path));
        if (value.isInt32()) return new BsonInt32(sameDigitsInt(value.asInt32().getValue(), collection, iteration, seed, path));
        if (value.isInt64()) return new BsonInt64(sameDigits(value.asInt64().getValue(), collection, iteration, seed, path));
        if (value.isDouble()) return new BsonDouble(sameShapeDecimal(BigDecimal.valueOf(value.asDouble().getValue()), collection, iteration, seed, path).doubleValue());
        if (value.isDecimal128()) return new BsonDecimal128(new Decimal128(sameShapeDecimal(value.asDecimal128().decimal128Value().bigDecimalValue(), collection, iteration, seed, path)));
        if (value.isBoolean()) return BsonBoolean.valueOf(unit(seed, collection, iteration, path) < 0.5);
        if (value.isDateTime()) return new BsonDateTime(sameDigits(value.asDateTime().getValue(), collection, iteration, seed, path));
        if (value.isTimestamp()) return new BsonTimestamp(
                sameDigitsInt(value.asTimestamp().getTime(), collection, iteration, seed, path),
                (int) boundedLong(hash(seed, collection, iteration, path + "#inc"), 0, 999));
        if (value.isObjectId()) return new BsonObjectId(new org.bson.types.ObjectId(Arrays.copyOf(hash(seed, collection, iteration, path), 12)));
        if (value.isBinary()) {
            byte[] data = new byte[value.asBinary().getData().length];
            for (int i = 0; i < data.length; i++)
                data[i] = (byte) boundedLong(hash(seed, collection, iteration, path + "#" + i), 0, 255);
            return new BsonBinary(data);
        }
        if (value.isRegularExpression()) return new BsonRegularExpression(
                randomText(value.asRegularExpression().getPattern().length(), collection, iteration, seed, path),
                value.asRegularExpression().getOptions());
        if (value.isJavaScript()) return new BsonJavaScript(randomText(value.asJavaScript().getCode().length(), collection, iteration, seed, path));
        if (value.isNull() || value instanceof BsonMinKey || value instanceof BsonMaxKey) return value;
        return BsonNull.VALUE;
    }
    private String randomText(int length, String collection, long iteration, long seed, String path) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            value.append(ALPHANUMERIC.charAt(betweenInt(seed, collection, iteration, path + "#" + i, 0, ALPHANUMERIC.length() - 1)));
        return value.toString();
    }
    /** Same digit count as the template, sign preserved: 12345 -> [10000, 99999], 0 -> [0, 9]. */
    private static long sameDigits(long template, String collection, long iteration, long seed, String path) {
        int digits = Long.toString(template).length() - (template < 0 ? 1 : 0);
        long min = digits == 1 ? 0 : POW10[digits - 1];
        long max = digits >= 19 ? Long.MAX_VALUE : POW10[digits] - 1;
        long value = boundedLong(hash(seed, collection, iteration, path), min, max);
        return template < 0 ? -value : value;
    }
    private static int sameDigitsInt(int template, String collection, long iteration, long seed, String path) {
        long value = sameDigits(template, collection, iteration, seed, path);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(value, Integer.MAX_VALUE));
    }
    /** Same digit count of the unscaled value and same decimals count: 12.34 -> any dd.dd, sign preserved. */
    private static BigDecimal sameShapeDecimal(BigDecimal template, String collection, long iteration, long seed, String path) {
        int digits = template.abs().unscaledValue().abs().toString().length();
        BigInteger min = digits == 1 ? BigInteger.ZERO : BigInteger.TEN.pow(digits - 1);
        BigInteger max = BigInteger.TEN.pow(digits).subtract(BigInteger.ONE);
        BigInteger unscaled = new BigInteger(1, hash(seed, collection, iteration, path))
                .mod(max.subtract(min).add(BigInteger.ONE)).add(min);
        BigDecimal result = new BigDecimal(unscaled, template.scale());
        return template.signum() < 0 ? result.negate() : result;
    }
    private static String batchUniqueRandomString(long seed, String collection, long iteration, String path,
                                                  String alphabet, int length, int batchSize) {
        int suffixLength = 0;
        long suffixSpace = 1;
        if (alphabet.length() == 1 && batchSize > 1)
            throw new GenerationException("randomString _id space is smaller than batchSize in " + collection);
        while (suffixSpace < batchSize) {
            suffixSpace = Math.multiplyExact(suffixSpace, alphabet.length());
            suffixLength++;
        }
        if (suffixLength > length)
            throw new GenerationException("randomString _id space is smaller than batchSize in " + collection);

        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length - suffixLength; i++)
            value.append(alphabet.charAt(betweenInt(seed, collection, iteration, path + "#" + i, 0, alphabet.length() - 1)));
        if (suffixLength == 0) return value.toString();

        long batch = iteration / batchSize;
        long position = iteration % batchSize;
        long offset = boundedLong(hash(seed, collection, batch, path + "#batchOffset"), 0, suffixSpace - 1);
        long step = boundedLong(hash(seed, collection, batch, path + "#batchStep"), 1, suffixSpace - 1);
        while (gcd(step, suffixSpace) != 1) {
            step++;
            if (step == suffixSpace) step = 1;
        }
        long encoded;
        if (suffixSpace < (1L << 31)) {
            encoded = Math.floorMod(step * position + offset, suffixSpace);
        } else {
            encoded = BigInteger.valueOf(step).multiply(BigInteger.valueOf(position)).add(BigInteger.valueOf(offset))
                    .mod(BigInteger.valueOf(suffixSpace)).longValueExact();
        }
        char[] suffix = new char[suffixLength];
        for (int i = suffixLength - 1; i >= 0; i--) {
            suffix[i] = alphabet.charAt((int)(encoded % alphabet.length()));
            encoded /= alphabet.length();
        }
        return value.append(suffix).toString();
    }
    private static long gcd(long left, long right) {
        while (right != 0) { long remainder = left % right; left = right; right = remainder; }
        return Math.abs(left);
    }
    private static int betweenInt(long seed,String collection,long iteration,String path,int min,int max){return (int)boundedLong(hash(seed,collection,iteration,path),min,max);}
    private static long boundedLong(byte[] hash, long min, long max) {
        if (min > max) throw new GenerationException("invalid numeric range");
        BigInteger range = BigInteger.valueOf(max).subtract(BigInteger.valueOf(min)).add(BigInteger.ONE);
        BigInteger value = new BigInteger(1, hash).mod(range).add(BigInteger.valueOf(min));
        return value.longValueExact();
    }
    private static double unit(long seed,String collection,long iteration,String path){long bits=ByteBuffer.wrap(hash(seed,collection,iteration,path)).getLong()>>>11;return bits*0x1.0p-53;}
    private static UUID uuid(long seed,String collection,long iteration,String path){byte[] h=hash(seed,collection,iteration,path);long most=ByteBuffer.wrap(h,0,8).getLong(),least=ByteBuffer.wrap(h,8,8).getLong();most=(most&0xffffffffffff0fffL)|0x0000000000004000L;least=(least&0x3fffffffffffffffL)|0x8000000000000000L;return new UUID(most,least);}
    private static byte[] hash(long seed,String collection,long iteration,String path){MessageDigest d=SHA_256.get();d.reset();d.update(ByteBuffer.allocate(16).putLong(seed).putLong(iteration).array());d.update(collection.getBytes(StandardCharsets.UTF_8));d.update((byte)0);d.update(path.getBytes(StandardCharsets.UTF_8));return d.digest();}
    private static long requiredStart(Map<String,Long> starts,String collection,String path){Long value=starts.get(collection+"\0"+path);if(value==null)throw new GenerationException("Missing resolved sequence start for "+collection+path);return value;}
}
