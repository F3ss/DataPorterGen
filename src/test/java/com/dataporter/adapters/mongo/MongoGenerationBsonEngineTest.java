package com.dataporter.adapters.mongo;

import com.dataporter.adapters.config.GenerationConfigReader;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.UnconfiguredFields;
import com.dataporter.shared.bson.BsonPayload;

import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class MongoGenerationBsonEngineTest {
    @Test void yamlIntegerLiteralsUseRangeAppropriateBsonTypesRecursively(@TempDir Path temp) throws Exception {
        Path config = temp.resolve("generation.yml");
        Files.writeString(config, """
                version: 1
                collections:
                  - name: QWE
                    count: 67000000
                    fields:
                      /ASD:
                        kind: literal
                        value: 1
                      /minInt: { kind: literal, value: -2147483648 }
                      /maxInt: { kind: literal, value: 2147483647 }
                      /belowMinInt: { kind: literal, value: -2147483649 }
                      /aboveMaxInt: { kind: literal, value: 2147483648 }
                      /nested:
                        kind: literal
                        value:
                          numbers: [2, 2147483649, { minimum: -2147483648, below: -2147483649 }]
                """);
        var collection = new GenerationConfigReader(config).read().collections().getFirst();
        var literal = (Literal) collection.fields().get("/ASD");

        BsonDocument generated = decode(new MongoGenerationBsonEngine().generate(
                collection.name(), 0, 99, encode(BsonDocument.parse("{_id: 'template'}")),
                collection.fields(), ResolvedIdStrategy.explicit(), Map.of(), Map.of()));

        assertThat(literal.value()).isEqualTo(1).isInstanceOf(Integer.class);
        assertThat(generated.get("ASD")).isEqualTo(new BsonInt32(1));
        assertThat(generated.get("ASD").isDocument()).isFalse();
        assertThat(generated.get("minInt")).isEqualTo(new BsonInt32(Integer.MIN_VALUE));
        assertThat(generated.get("maxInt")).isEqualTo(new BsonInt32(Integer.MAX_VALUE));
        assertThat(generated.get("belowMinInt")).isEqualTo(new BsonInt64((long) Integer.MIN_VALUE - 1));
        assertThat(generated.get("aboveMaxInt")).isEqualTo(new BsonInt64((long) Integer.MAX_VALUE + 1));
        BsonArray numbers = generated.getDocument("nested").getArray("numbers");
        assertThat(numbers.get(0)).isEqualTo(new BsonInt32(2));
        assertThat(numbers.get(1)).isEqualTo(new BsonInt64(2_147_483_649L));
        assertThat(numbers.get(2).asDocument().get("minimum")).isEqualTo(new BsonInt32(Integer.MIN_VALUE));
        assertThat(numbers.get(2).asDocument().get("below"))
                .isEqualTo(new BsonInt64((long) Integer.MIN_VALUE - 1));
    }

    @Test void sharedDateUsesOneMillisecondInstantAcrossCollectionsAndFormats() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse("{_id: 1}"));
        Map<String, SharedDateDefinition> sharedDates = Map.of("operationDate", new SharedDateDefinition(
                new FixedDate(java.time.Instant.parse("2026-09-02T10:20:30.123456Z"))));
        Map<String, GenerationRule> eventFields = Map.of(
                "/createdAt", new DateTime(new SharedDateRef("operationDate"), DateOutput.BSON_DATE,
                        null, "UTC", "ROOT", RuleOptions.REQUIRED),
                "/createdText", new DateTime(new SharedDateRef("operationDate"), DateOutput.STRING,
                        "uuuu-MM-dd'T'HH:mm:ss.SSSX", "UTC", "ROOT", RuleOptions.REQUIRED));
        Map<String, GenerationRule> legacyFields = Map.of(
                "/legacyDate", new DateTime(new SharedDateRef("operationDate"), DateOutput.STRING,
                        "'1'yyMMdd", "UTC", "ROOT", RuleOptions.REQUIRED));

        BsonDocument event = decode(engine.generate("events", 7, 99, template, eventFields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), sharedDates, null, 1, null, UnconfiguredFields.SNAPSHOT));
        BsonDocument legacy = decode(engine.generate("legacyEvents", 7, 99, template, legacyFields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), sharedDates, null, 1, null, UnconfiguredFields.SNAPSHOT));

        assertThat(event.getDateTime("createdAt").getValue()).isEqualTo(1_788_344_430_123L);
        assertThat(event.getString("createdText").getValue()).isEqualTo("2026-09-02T10:20:30.123Z");
        assertThat(legacy.getString("legacyDate").getValue()).isEqualTo("1260902");
    }

    @Test void randomSharedDateIsCoordinateDerivedOnlyFromSeedNameAndIteration() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse("{_id: 1}"));
        Map<String, SharedDateDefinition> sharedDates = Map.of("operationDate", new SharedDateDefinition(
                new RandomDateRange(java.time.Instant.parse("2025-01-01T00:00:00Z"),
                        java.time.Instant.parse("2026-12-31T23:59:59.999Z"))));
        Map<String, GenerationRule> firstFields = Map.of("/first", new DateTime(
                new SharedDateRef("operationDate"), DateOutput.BSON_DATE, null, "UTC", "ROOT", RuleOptions.REQUIRED));
        Map<String, GenerationRule> secondFields = Map.of("/second", new DateTime(
                new SharedDateRef("operationDate"), DateOutput.BSON_DATE, null, "UTC", "ROOT", RuleOptions.REQUIRED));

        BsonDocument first = decode(engine.generate("first", 42, 123, template, firstFields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), sharedDates, null, 1, null, UnconfiguredFields.SNAPSHOT));
        BsonDocument second = decode(engine.generate("second", 42, 123, template, secondFields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), sharedDates, null, 1, null, UnconfiguredFields.SNAPSHOT));
        BsonDocument repeated = decode(engine.generate("second", 42, 123, template, secondFields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), sharedDates, null, 1, null, UnconfiguredFields.SNAPSHOT));
        BsonDocument nextIteration = decode(engine.generate("second", 43, 123, template, secondFields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), sharedDates, null, 1, null, UnconfiguredFields.SNAPSHOT));

        assertThat(first.getDateTime("first").getValue()).isEqualTo(second.getDateTime("second").getValue());
        assertThat(repeated.getDateTime("second")).isEqualTo(second.getDateTime("second"));
        assertThat(nextIteration.getDateTime("second")).isNotEqualTo(second.getDateTime("second"));
    }

    @Test void generationIsDeterministicAndSupportsPointersRefsConcatNullAndOmit() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse("{_id: 1, keep: {x: 7}, remove: 'old', existingArray: [1, 2]}"));
        Map<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/sequence", new Sequence(SequenceStart.EXPLICIT, 10, 2, RuleOptions.REQUIRED));
        fields.put("/code", new RandomString(Alphabet.UPPER_LATIN, null, 6, 6, RuleOptions.REQUIRED));
        fields.put("/joined", new Concat(List.of(new Literal("ID-", RuleOptions.REQUIRED),
                new Ref(null, "/sequence", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED));
        fields.put("/remove", new Literal("ignored", new RuleOptions(0, 1)));
        fields.put("/nullable", new Literal("ignored", new RuleOptions(1, 0)));
        Map<String,GenerationRule> nested = new LinkedHashMap<>();
        nested.put("copy", new Ref(null, "/profile/label", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        nested.put("label", new Literal("nested", RuleOptions.REQUIRED));
        fields.put("/profile", new ObjectValue(nested, RuleOptions.REQUIRED));
        fields.put("/existingArray/1", new Literal(9, RuleOptions.REQUIRED));

        BsonPayload first = engine.generate("items", 3, 99, template, fields, ResolvedIdStrategy.explicit(), Map.of(), Map.of());
        BsonPayload second = engine.generate("items", 3, 99, template, fields, ResolvedIdStrategy.explicit(), Map.of(), Map.of());
        BsonDocument result = new RawBsonDocument(first.bytes());

        assertThat(first).isEqualTo(second);
        assertThat(result.getInt64("sequence").getValue()).isEqualTo(16);
        assertThat(result.getString("joined").getValue()).isEqualTo("ID-16");
        assertThat(result.getString("code").getValue()).hasSize(6);
        assertThat(result).doesNotContainKey("remove");
        assertThat(result.get("nullable").isNull()).isTrue();
        assertThat(result.getDocument("keep").getInt32("x").getValue()).isEqualTo(7);
        assertThat(result.getDocument("profile").getString("copy").getValue()).isEqualTo("nested");
        assertThat(result.getArray("existingArray").get(1).asInt32().getValue()).isEqualTo(9);
    }

    @Test void randomStringIdIsUniqueInsideEachBatchWhileOrdinaryStringsMayRepeat() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse("{_id: 'old'}"));
        Map<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/_id", new RandomString(Alphabet.CUSTOM, "AB", 2, 2, RuleOptions.REQUIRED));
        fields.put("/short", new RandomString(Alphabet.CUSTOM, "X", 1, 1, RuleOptions.REQUIRED));

        List<BsonDocument> documents = new ArrayList<>();
        for (int iteration = 0; iteration < 8; iteration++) {
            BsonPayload payload = engine.generate("items", iteration, 99, template, fields,
                    ResolvedIdStrategy.explicit(), Map.of(), Map.of(), "/_id", 4);
            documents.add(new RawBsonDocument(payload.bytes()));
        }

        assertThat(documents.subList(0, 4)).extracting(document -> document.getString("_id").getValue())
                .doesNotHaveDuplicates();
        assertThat(documents.subList(4, 8)).extracting(document -> document.getString("_id").getValue())
                .doesNotHaveDuplicates();
        assertThat(documents).extracting(document -> document.getString("short").getValue())
                .containsOnly("X");
        BsonPayload repeated = engine.generate("items", 3, 99, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), "/_id", 4);
        assertThat(repeated).isEqualTo(encode(documents.get(3)));
    }

    @Test void compositeIdUsesBatchUniqueGeneratedFieldAndScalarValuesFromOneRawTemplate() {
        var engine = new MongoGenerationBsonEngine();
        org.bson.types.ObjectId objectId = new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b");
        BsonPayload template = encode(new BsonDocument("_id", new BsonString("old"))
                .append("text", new BsonString("T"))
                .append("bool", BsonBoolean.TRUE)
                .append("i32", new BsonInt32(3))
                .append("i64", new BsonInt64(4))
                .append("double", new BsonDouble(1.5))
                .append("decimal", new BsonDecimal128(new Decimal128(new java.math.BigDecimal("2.50"))))
                .append("oid", new BsonObjectId(objectId))
                .append("preserved", new BsonBinary(new byte[]{1, 2, 3})));
        LinkedHashMap<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/generated", new RandomString(Alphabet.CUSTOM, "AB", 2, 2, RuleOptions.REQUIRED));
        List<GenerationRule> parts = new ArrayList<>();
        parts.add(new Ref(null, "/generated", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        for (String path : List.of("/text", "/bool", "/i32", "/i64", "/double", "/decimal", "/oid")) {
            parts.add(new Literal("|", RuleOptions.REQUIRED));
            parts.add(new Ref(null, path, MissingPolicy.ERROR, RuleOptions.REQUIRED));
        }
        fields.put("/_id", new Concat(parts, RuleOptions.REQUIRED));

        List<BsonDocument> documents = new ArrayList<>();
        for (int iteration = 0; iteration < 4; iteration++) {
            BsonPayload generated = engine.generate("items", iteration, 99, template, fields,
                    ResolvedIdStrategy.explicit(), Map.of(), Map.of(), "/generated", 4);
            documents.add(new RawBsonDocument(generated.bytes()));
        }

        assertThat(documents).extracting(document -> document.getString("_id").getValue())
                .allMatch(id -> id.endsWith("|T|true|3|4|1.5|2.50|" + objectId.toHexString()))
                .doesNotHaveDuplicates();
        assertThat(documents).allSatisfy(document -> assertThat(document.getBinary("preserved").getData())
                .containsExactly(1, 2, 3));
    }

    @Test void randomAlphaNumStringBetweenIsExactWidthUppercaseBoundedDeterministicAndRecursive() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse("{_id: 1, removed: 'old'}"));
        List<RandomAlphaNumStringBetween> ranges = List.of(
                alphaNum(10_000_000, 1_000_000_000),
                alphaNum(0, 392_000),
                alphaNum(1_000_000, 1_001_800),
                alphaNum(2_000_000, 2_000_002));
        LinkedHashMap<String,GenerationRule> fields = new LinkedHashMap<>();
        for (int i = 0; i < ranges.size(); i++) fields.put("/range" + i, ranges.get(i));
        fields.put("/tiny", alphaNum(0, 2));
        fields.put("/copy", new Ref(null, "/range0", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        fields.put("/joined", new Concat(List.of(new Literal("C-", RuleOptions.REQUIRED),
                alphaNum(0, 392_000)), RuleOptions.REQUIRED));
        fields.put("/values", new Array(new LengthRange(2, 2), alphaNum(0, 392_000), RuleOptions.REQUIRED));
        fields.put("/nested", new ObjectValue(Map.of("code", alphaNum(0, 392_000)), RuleOptions.REQUIRED));
        fields.put("/nullable", new RandomAlphaNumStringBetween(BigInteger.ZERO, BigInteger.TWO, 6,
                new RuleOptions(1, 0)));
        fields.put("/removed", new RandomAlphaNumStringBetween(BigInteger.ZERO, BigInteger.TWO, 6,
                new RuleOptions(0, 1)));

        BsonPayload first = engine.generate("items", 7, 99, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of());
        BsonPayload repeated = engine.generate("items", 7, 99, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of());
        BsonPayload otherSeed = engine.generate("items", 7, 100, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of());
        BsonDocument result = new RawBsonDocument(first.bytes());

        assertThat(first).isEqualTo(repeated);
        assertThat(new RawBsonDocument(otherSeed.bytes()).getString("range0"))
                .isNotEqualTo(result.getString("range0"));
        for (int i = 0; i < ranges.size(); i++) {
            String value = result.getString("range" + i).getValue();
            assertThat(value).hasSize(6).matches("[0-9A-Z]{6}");
            BigInteger decoded = new BigInteger(value, 36);
            assertThat(decoded).isGreaterThanOrEqualTo(ranges.get(i).min()).isLessThan(ranges.get(i).max());
        }
        assertThat(result.getString("tiny").getValue()).matches("00000[01]");
        assertThat(result.getString("copy")).isEqualTo(result.getString("range0"));
        assertThat(result.getString("joined").getValue()).matches("C-[0-9A-Z]{6}");
        assertThat(result.getArray("values")).allSatisfy(value ->
                assertThat(value.asString().getValue()).matches("[0-9A-Z]{6}"));
        assertThat(result.getDocument("nested").getString("code").getValue()).matches("[0-9A-Z]{6}");
        assertThat(result.get("nullable").isNull()).isTrue();
        assertThat(result).doesNotContainKey("removed");
    }

    @Test void weightedChoiceEvaluatesSelectedNestedRuleAtTheFieldPath() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse("{_id: 'old', source: 'template'}"));
        WeightedChoice id = new WeightedChoice(List.of(
                new Choice(new RandomAlphaNumStringBetween(BigInteger.ZERO, BigInteger.TWO, 2,
                        RuleOptions.REQUIRED), 1),
                new Choice(new RandomAlphaNumStringBetween(BigInteger.valueOf(36), BigInteger.valueOf(38), 2,
                        RuleOptions.REQUIRED), 1)), RuleOptions.REQUIRED);
        WeightedChoice copy = new WeightedChoice(List.of(
                new Choice(new Ref(null, "/source", MissingPolicy.ERROR, RuleOptions.REQUIRED), 1)),
                RuleOptions.REQUIRED);
        Map<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/_id", id);
        fields.put("/copy", copy);

        Set<String> ids = new HashSet<>();
        for (int iteration = 0; iteration < 64; iteration++) {
            BsonPayload first = engine.generate("items", iteration, 99, template, fields,
                    ResolvedIdStrategy.explicit(), Map.of(), Map.of());
            BsonPayload repeated = engine.generate("items", iteration, 99, template, fields,
                    ResolvedIdStrategy.explicit(), Map.of(), Map.of());
            assertThat(first).isEqualTo(repeated);
            BsonDocument document = new RawBsonDocument(first.bytes());
            ids.add(document.getString("_id").getValue());
            assertThat(document.getString("copy").getValue()).isEqualTo("template");
        }

        assertThat(ids).allMatch(value -> value.matches("0[01]|1[01]"));
        assertThat(ids).anyMatch(value -> value.startsWith("0"));
        assertThat(ids).anyMatch(value -> value.startsWith("1"));
    }

    @Test void weightedChoiceAndSelectedRuleUseIndependentPresenceCoordinates() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse("{_id: 1, value: 'template'}"));
        WeightedChoice choice = new WeightedChoice(List.of(new Choice(
                new Literal("generated", new RuleOptions(0, .5)), 1)), new RuleOptions(.5, 0));
        Map<String,GenerationRule> fields = Map.of("/value", choice);
        boolean sawNull = false;
        boolean sawOmitted = false;
        boolean sawValue = false;

        for (int iteration = 0; iteration < 256; iteration++) {
            BsonDocument document = new RawBsonDocument(engine.generate("items", iteration, 99, template, fields,
                    ResolvedIdStrategy.explicit(), Map.of(), Map.of()).bytes());
            if (!document.containsKey("value")) sawOmitted = true;
            else if (document.get("value").isNull()) sawNull = true;
            else if (document.getString("value").getValue().equals("generated")) sawValue = true;
        }

        assertThat(sawNull).isTrue();
        assertThat(sawOmitted).isTrue();
        assertThat(sawValue).isTrue();
    }

    @Test void prunesTemplateToKeepPathsBeforeRules() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse(
                "{_id: 1, keep: 7, drop: 'x', nested: {a: 1, b: 2}, arr: [1, 2]}"));
        Map<String,GenerationRule> fields = Map.of("/keep", new Literal(9, RuleOptions.REQUIRED));
        Set<String> keep = new LinkedHashSet<>(List.of("/_id", "/keep", "/nested/a"));

        BsonDocument pruned = decode(engine.generate("items", 0, 99, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), Map.of(), null, 1, keep,
                UnconfiguredFields.OMIT));

        assertThat(pruned.keySet()).containsExactly("_id", "keep", "nested");
        assertThat(pruned.get("keep")).isEqualTo(new BsonInt32(9));
        assertThat(pruned.getDocument("nested").keySet()).containsExactly("a");
    }

    @Test void keepPathInsideArrayKeepsTheWholeArrayAndNullKeepPathIsByteIdentical() {
        var engine = new MongoGenerationBsonEngine();
        BsonPayload template = encode(BsonDocument.parse(
                "{_id: 1, keep: 7, drop: 'x', nested: {a: 1, b: 2}, arr: [1, 2]}"));
        Map<String,GenerationRule> fields = Map.of("/keep", new Literal(9, RuleOptions.REQUIRED));

        BsonDocument array = decode(engine.generate("items", 0, 99, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), Map.of(), null, 1,
                new LinkedHashSet<>(List.of("/_id", "/arr/1")), UnconfiguredFields.OMIT));
        assertThat(array.keySet()).containsExactly("_id", "arr", "keep");
        assertThat(array.getArray("arr")).isEqualTo(new BsonArray(List.of(new BsonInt32(1), new BsonInt32(2))));

        BsonPayload nullable = engine.generate("items", 0, 99, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), Map.of(), null, 1, null,
                UnconfiguredFields.SNAPSHOT);
        BsonPayload legacy = engine.generate("items", 0, 99, template, fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of());
        assertThat(nullable.bytes()).containsExactly(legacy.bytes());
    }

    @Test void blanksUnkeptTemplateValuesWithTypeDefaults() {
        var engine = new MongoGenerationBsonEngine();
        BsonDocument template = new BsonDocument("_id", new BsonInt32(1))
                .append("keep", new BsonInt32(7))
                .append("str", new BsonString("text"))
                .append("flag", BsonBoolean.TRUE)
                .append("num", new BsonInt32(42))
                .append("big", new BsonInt64(99))
                .append("ratio", new BsonDouble(2.5))
                .append("when", new BsonDateTime(1_725_000_000_000L))
                .append("arr", new BsonArray(List.of(new BsonInt32(1), new BsonInt32(2))))
                .append("nested", new BsonDocument("a", new BsonInt32(1)).append("b", new BsonString("x")));
        Map<String,GenerationRule> fields = Map.of("/keep", new Literal(9, RuleOptions.REQUIRED));

        BsonDocument blanked = decode(engine.generate("items", 0, 99, encode(template), fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), Map.of(), null, 1,
                new LinkedHashSet<>(List.of("/_id", "/keep")), UnconfiguredFields.DEFAULTS));

        assertThat(blanked.keySet()).containsExactly(
                "_id", "keep", "str", "flag", "num", "big", "ratio", "when", "arr", "nested");
        assertThat(blanked.getInt32("keep").getValue()).isEqualTo(9);
        assertThat(blanked.getString("str").getValue()).isEmpty();
        assertThat(blanked.getBoolean("flag").getValue()).isFalse();
        assertThat(blanked.getInt32("num").getValue()).isZero();
        assertThat(blanked.getInt64("big").getValue()).isZero();
        assertThat(blanked.getDouble("ratio").getValue()).isZero();
        assertThat(blanked.getDateTime("when").getValue()).isZero();
        assertThat(blanked.getArray("arr")).isEmpty();
        assertThat(blanked.getDocument("nested"))
                .isEqualTo(new BsonDocument("a", new BsonInt32(0)).append("b", new BsonString("")));
    }

    @Test void randomizesUnkeptValuesWithSameShapeDeterministically() {
        var engine = new MongoGenerationBsonEngine();
        BsonDocument template = new BsonDocument("_id", new BsonInt32(1))
                .append("keep", new BsonInt32(7))
                .append("str", new BsonString("abcdef"))
                .append("num", new BsonInt32(12345))
                .append("big", new BsonInt64(-987654L))
                .append("ratio", new BsonDouble(12.34))
                .append("arr", new BsonArray(List.of(new BsonString("aa"), new BsonInt32(7))));
        Map<String,GenerationRule> fields = Map.of("/keep", new Literal(9, RuleOptions.REQUIRED));
        Set<String> keep = new LinkedHashSet<>(List.of("/_id", "/keep"));

        BsonDocument randomized = decode(engine.generate("items", 0, 99, encode(template), fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), Map.of(), null, 1, keep, UnconfiguredFields.RANDOM));
        BsonDocument repeated = decode(engine.generate("items", 0, 99, encode(template), fields,
                ResolvedIdStrategy.explicit(), Map.of(), Map.of(), Map.of(), null, 1, keep, UnconfiguredFields.RANDOM));

        assertThat(randomized).isEqualTo(repeated);
        assertThat(randomized.getInt32("keep").getValue()).isEqualTo(9);
        assertThat(randomized.getString("str").getValue()).hasSize(6).isNotEqualTo("abcdef");
        assertThat(Long.toString(Math.abs(randomized.getInt32("num").getValue()))).hasSize(5);
        assertThat(randomized.getInt32("num").getValue()).isNotEqualTo(12345);
        assertThat(randomized.getInt64("big").getValue()).isNegative();
        assertThat(Long.toString(Math.abs(randomized.getInt64("big").getValue()))).hasSize(6);
        double ratioValue = randomized.getDouble("ratio").getValue();
        // Two integer digits and at most two decimals, mirroring the 12.34 template shape.
        assertThat(ratioValue).isStrictlyBetween(9.99, 100.0);
        assertThat(java.math.BigDecimal.valueOf(ratioValue).scale()).isLessThanOrEqualTo(2);
        assertThat(ratioValue).isNotEqualTo(12.34);
        assertThat(randomized.getArray("arr").size()).isEqualTo(2);
        assertThat(randomized.getArray("arr").get(0).asString().getValue()).hasSize(2);
        assertThat(randomized.getArray("arr").get(1).isInt32()).isTrue();
    }

    private RandomAlphaNumStringBetween alphaNum(long min, long max) {
        return new RandomAlphaNumStringBetween(BigInteger.valueOf(min), BigInteger.valueOf(max), 6,
                RuleOptions.REQUIRED);
    }

    private BsonPayload encode(BsonDocument document) {
        RawBsonDocument raw = new RawBsonDocument(document, new BsonDocumentCodec());
        return new BsonPayload(Arrays.copyOfRange(raw.getBackingArray(), raw.getByteOffset(), raw.getByteOffset()+raw.getByteLength()));
    }

    private BsonDocument decode(BsonPayload payload) { return new RawBsonDocument(payload.bytes()); }
}
