package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.shared.bson.BsonPayload;

import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins the byte-exact output of the generation engine over every rule kind, every resolved _id
 * strategy, and batch-unique id boundaries. Any change that alters generated values must turn this
 * red; performance work must keep it green. To re-baseline after an intentional contract change,
 * set the constant to PIN-ME, run the test, and copy the printed digest.
 */
class MongoGenerationBsonEngineGoldenTest {
    private static final String GOLDEN_SHA256 = "ec7a5e50d3f76c5eebcc1a05ddbe87934df668be4df32105913fd0dead8d54b1";
    private static final long SEED = 20260901L;
    private static final int ITERATIONS = 10_000;
    private static final int BATCH_SIZE = 64;

    private final MongoGenerationBsonEngine engine = new MongoGenerationBsonEngine();

    @Test void engineOutputIsByteStableAcrossAllRuleKindsAndIdStrategies() {
        List<BsonPayload> templates = List.of(
                encode(new BsonDocument("_id", new BsonString("old"))
                        .append("text", new BsonString("T")).append("flag", BsonBoolean.TRUE)
                        .append("when", new BsonDateTime(1_700_000_000_000L))
                        .append("sub", new BsonDocument("label", new BsonString("L"))
                                .append("deep", new BsonDocument("n", new BsonInt32(5))))
                        .append("arr", new BsonArray(List.of(new BsonInt32(1), new BsonInt32(2))))),
                encode(new BsonDocument("_id", new BsonInt64(2))
                        .append("text", new BsonString("U")).append("flag", BsonBoolean.FALSE)
                        .append("when", new BsonDateTime(1_700_000_001_000L))
                        .append("sub", new BsonDocument("label", new BsonString("M"))
                                .append("deep", new BsonDocument("n", new BsonInt32(6))))
                        .append("arr", new BsonArray(List.of(new BsonInt32(9))))),
                encode(new BsonDocument("_id", new BsonObjectId(new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b")))
                        .append("text", new BsonString("")).append("flag", BsonBoolean.TRUE)
                        .append("when", new BsonDateTime(1_699_999_999_000L))
                        .append("sub", new BsonDocument("label", new BsonString("Z"))
                                .append("deep", new BsonDocument("n", new BsonInt32(7))))
                        .append("arr", new BsonArray(List.of(new BsonInt32(7))))));

        Map<String, GenerationRule> alphaFields = new LinkedHashMap<>();
        alphaFields.put("/_id", new Concat(List.of(new Ref(null, "/alphaRand", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Literal("-", RuleOptions.REQUIRED), new Ref(null, "/seq", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Literal("-", RuleOptions.REQUIRED), new Ref(null, "/alphaDate", MissingPolicy.ERROR, RuleOptions.REQUIRED)),
                RuleOptions.REQUIRED));
        alphaFields.put("/alphaRand", new RandomString(Alphabet.CUSTOM, "AB", 8, 8, RuleOptions.REQUIRED));
        alphaFields.put("/seq", new Sequence(SequenceStart.EXPLICIT, 10, 3, RuleOptions.REQUIRED));
        alphaFields.put("/alphaDate", new DateTime(new FixedDate(Instant.parse("2026-01-15T10:00:00Z")),
                DateOutput.STRING, "yyyy-MM-dd", "UTC", "ROOT", RuleOptions.REQUIRED));
        alphaFields.put("/opt", new Literal("x", new RuleOptions(0.3, 0.3)));
        alphaFields.put("/hex", new RandomString(Alphabet.HEX, null, 3, 9, RuleOptions.REQUIRED));
        alphaFields.put("/upper", new RandomString(Alphabet.UPPER_LATIN, null, 6, 6, RuleOptions.REQUIRED));
        alphaFields.put("/alphaNumBetween", new RandomAlphaNumStringBetween(BigInteger.valueOf(10_000_000),
                BigInteger.valueOf(1_000_000_000), 6, RuleOptions.REQUIRED));
        alphaFields.put("/lower", new RandomString(Alphabet.LOWER_LATIN, null, 4, 5, new RuleOptions(0.1, 0)));
        alphaFields.put("/n32", new RandomNumber(NumberType.INT32, BigDecimal.valueOf(-100), BigDecimal.valueOf(100), RuleOptions.REQUIRED));
        alphaFields.put("/n64", new RandomNumber(NumberType.INT64, BigDecimal.valueOf(-1_000_000_000_000L), BigDecimal.valueOf(1_000_000_000_000L), RuleOptions.REQUIRED));
        alphaFields.put("/ndbl", new RandomNumber(NumberType.DOUBLE, new BigDecimal("-1.5"), new BigDecimal("2.5"), RuleOptions.REQUIRED));
        alphaFields.put("/ndec", new RandomNumber(NumberType.DECIMAL128, new BigDecimal("0.00"), new BigDecimal("9.99"), RuleOptions.REQUIRED));
        alphaFields.put("/pick", new WeightedChoice(List.of(new Choice("a", 1), new Choice("b", 2), new Choice(3, 0.5)), RuleOptions.REQUIRED));
        alphaFields.put("/flag", new RandomBoolean(0.3, RuleOptions.REQUIRED));
        alphaFields.put("/autoSeq", new Sequence(SequenceStart.AUTO_AFTER_TARGET_MAX, 0, 5, RuleOptions.REQUIRED));
        alphaFields.put("/oid", new ObjectId(RuleOptions.REQUIRED));
        alphaFields.put("/uuidBin", new Uuid(UuidOutput.BSON_BINARY, RuleOptions.REQUIRED));
        alphaFields.put("/uuidStr", new Uuid(UuidOutput.STRING, RuleOptions.REQUIRED));
        alphaFields.put("/dateFixed", new DateTime(new FixedDate(Instant.parse("2026-02-01T00:00:00Z")), DateOutput.BSON_DATE, null, null, null, RuleOptions.REQUIRED));
        alphaFields.put("/dateRange", new DateTime(new RandomDateRange(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T23:59:59Z")),
                DateOutput.STRING, "yyyy-MM-dd'T'HH:mm:ss", "UTC", "ROOT", RuleOptions.REQUIRED));
        alphaFields.put("/dateRef", new DateTime(new DateRef(null, "/when"), DateOutput.BSON_DATE, null, null, null, RuleOptions.REQUIRED));
        alphaFields.put("/joined", new Concat(List.of(new Literal("v=", RuleOptions.REQUIRED),
                new Ref(null, "/n32", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Literal("/", RuleOptions.REQUIRED),
                new Ref(null, "/upper", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED));
        alphaFields.put("/missingNull", new Ref(null, "/nope", MissingPolicy.NULL, RuleOptions.REQUIRED));
        alphaFields.put("/missingOmit", new Ref(null, "/nope", MissingPolicy.OMIT, RuleOptions.REQUIRED));
        alphaFields.put("/optArr", new Array(new LengthRange(0, 3), new RandomString(Alphabet.ALPHANUMERIC, null, 1, 3, RuleOptions.REQUIRED), RuleOptions.REQUIRED));
        alphaFields.put("/sub/deep/n", new Ref(null, "/sub/deep/n", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        LinkedHashMap<String, GenerationRule> nested = new LinkedHashMap<>();
        nested.put("label", new Literal("nested", RuleOptions.REQUIRED));
        nested.put("inner", new Ref(null, "/sub/deep/n", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        nested.put("copy", new Ref(null, "/profile/label", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        alphaFields.put("/profile", new ObjectValue(nested, RuleOptions.REQUIRED));

        Map<String, GenerationRule> betaFields = new LinkedHashMap<>();
        betaFields.put("/fromAlphaId", new Ref("alpha", "/_id", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        betaFields.put("/fromAlphaText", new Ref("alpha", "/text", MissingPolicy.ERROR, RuleOptions.REQUIRED));
        betaFields.put("/betaCode", new RandomString(Alphabet.ALPHANUMERIC, null, 5, 12, RuleOptions.REQUIRED));

        Map<String, GenerationRule> gammaFields = new LinkedHashMap<>();
        gammaFields.put("/g", new RandomNumber(NumberType.INT64, BigDecimal.ZERO, BigDecimal.valueOf(9_999_999), RuleOptions.REQUIRED));
        gammaFields.put("/refLocal", new Ref(null, "/g", MissingPolicy.ERROR, RuleOptions.REQUIRED));

        Map<String, GenerationRule> deltaFields = new LinkedHashMap<>();
        deltaFields.put("/dk", new RandomString(Alphabet.UPPER_LATIN, null, 10, 10, RuleOptions.REQUIRED));

        Map<String, Long> sequenceStarts = Map.of("alpha\0/autoSeq", 777L);
        ResolvedIdStrategy alphaIds = ResolvedIdStrategy.explicit();
        ResolvedIdStrategy betaIds = new ResolvedIdStrategy(ResolvedIdStrategy.Kind.DETERMINISTIC_OBJECT_ID, "", 0);
        ResolvedIdStrategy gammaIds = new ResolvedIdStrategy(ResolvedIdStrategy.Kind.NUMERIC_SEQUENCE, "", 1000);
        ResolvedIdStrategy deltaIds = new ResolvedIdStrategy(ResolvedIdStrategy.Kind.FIELD_REFERENCE, "/dk", 0);

        MessageDigest sha = sha256();
        for (long iteration = 0; iteration < ITERATIONS; iteration++) {
            BsonPayload template = templates.get((int) (iteration % templates.size()));
            Map<String, BsonPayload> sameIteration = new LinkedHashMap<>();
            BsonPayload alpha = engine.generate("alpha", iteration, SEED, template, alphaFields, alphaIds,
                    sameIteration, sequenceStarts, "/alphaRand", BATCH_SIZE);
            sameIteration.put("alpha", alpha);
            BsonPayload beta = engine.generate("beta", iteration, SEED, template, betaFields, betaIds, sameIteration, Map.of());
            BsonPayload gamma = engine.generate("gamma", iteration, SEED, template, gammaFields, gammaIds, sameIteration, Map.of());
            BsonPayload delta = engine.generate("delta", iteration, SEED, template, deltaFields, deltaIds, sameIteration, Map.of());
            for (BsonPayload payload : List.of(alpha, beta, gamma, delta)) absorb(sha, payload);
        }
        absorb(sha, engine.generate("alpha", 0, SEED, templates.get(0), alphaFields, alphaIds,
                Map.of(), sequenceStarts, "/alphaRand", BATCH_SIZE));
        absorb(sha, engine.generate("alpha", BATCH_SIZE, SEED, templates.get(0), alphaFields, alphaIds,
                Map.of(), sequenceStarts, "/alphaRand", BATCH_SIZE));
        sha.update(engine.inspect(templates.get(0)).toString().getBytes(StandardCharsets.UTF_8));
        BsonPayload lastAlpha = engine.generate("alpha", ITERATIONS - 1, SEED, templates.get(2), alphaFields,
                alphaIds, Map.of(), sequenceStarts, "/alphaRand", BATCH_SIZE);
        engine.validateScalarId(lastAlpha, "alpha");
        absorb(sha, engine.constraintKey(lastAlpha,
                new UniqueConstraint("alpha", "_id_", List.of("/_id"), false, false, false)));
        absorb(sha, engine.constraintKey(lastAlpha,
                new UniqueConstraint("alpha", "joined_1", List.of("/joined", "/n32"), false, false, false)));

        String actual = hex(sha.digest());
        if (GOLDEN_SHA256.equals("PIN-ME")) fail("Unpinned golden digest; set GOLDEN_SHA256 to: " + actual);
        assertThat(actual).isEqualTo(GOLDEN_SHA256);
    }

    private static void absorb(MessageDigest sha, BsonPayload payload) {
        sha.update(longBytes(payload.size()));
        sha.update(payload.bytes());
    }

    private static byte[] longBytes(long value) {
        return new byte[] { (byte) (value >>> 56), (byte) (value >>> 48), (byte) (value >>> 40), (byte) (value >>> 32),
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private BsonPayload encode(BsonDocument document) {
        RawBsonDocument raw = new RawBsonDocument(document, new BsonDocumentCodec());
        return new BsonPayload(Arrays.copyOfRange(raw.getBackingArray(), raw.getByteOffset(), raw.getByteOffset() + raw.getByteLength()));
    }
}
