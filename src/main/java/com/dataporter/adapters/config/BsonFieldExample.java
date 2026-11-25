package com.dataporter.adapters.config;

import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/** Renders bounded synthetic values for YAML comments without using source document values. */
final class BsonFieldExample {
    private static final HexFormat HEX = HexFormat.of();
    private static final long DATE_START = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
    private static final long DATE_END = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();

    private BsonFieldExample() {}

    static String render(String collection, String path, List<String> sortedTypes) {
        // A stable seed keeps the same hints byte-for-byte on repeated validation.
        long seed = ((long) collection.hashCode() << 32) | Integer.toUnsignedLong(path.hashCode());
        SplittableRandom random = new SplittableRandom(seed);
        List<String> knownTypes = sortedTypes.stream().filter(type -> !type.equals("NULL") && !type.equals("UNKNOWN")).toList();
        String type = knownTypes.isEmpty() ? "NULL" : knownTypes.get(random.nextInt(knownTypes.size()));
        return switch (type) {
            case "STRING" -> "\"sample-" + HEX.toHexDigits(random.nextInt()) + "\"";
            case "BOOLEAN" -> Boolean.toString(random.nextBoolean());
            case "INT32" -> Integer.toString(random.nextInt(1, 10_000));
            case "INT64" -> wrappedString("$numberLong", Long.toString(random.nextLong(1, 100_000)));
            case "DOUBLE" -> Double.toString(random.nextInt(1, 10_000) / 100.0);
            case "DECIMAL128" -> wrappedString("$numberDecimal", Double.toString(random.nextInt(1, 10_000) / 100.0));
            case "DATE_TIME" -> wrappedString("$date",
                    Instant.ofEpochSecond(random.nextLong(DATE_START, DATE_END)).toString());
            case "OBJECT_ID" -> objectId(random);
            case "UUID" -> wrappedString("$uuid", UUID.nameUUIDFromBytes(randomBytes(random, 16)).toString());
            case "BINARY" -> """
                    {"$binary":{"base64":"%s","subType":"00"}}""".formatted(
                    Base64.getEncoder().encodeToString(randomBytes(random, 4)));
            case "ARRAY" -> "[]";
            case "DOCUMENT" -> "{}";
            case "TIMESTAMP" -> "{\"$timestamp\":{\"t\":" + random.nextLong(DATE_START, DATE_END)
                    + ",\"i\":" + random.nextInt(100) + "}}";
            case "REGULAR_EXPRESSION" -> "{\"$regularExpression\":{\"pattern\":\"^sample-"
                    + HEX.toHexDigits(random.nextInt()) + "$\",\"options\":\"\"}}";
            case "SYMBOL" -> wrappedString("$symbol", "sample-" + HEX.toHexDigits(random.nextInt()));
            case "JAVASCRIPT" -> wrappedString("$code", Integer.toString(random.nextInt(1, 100)));
            case "JAVASCRIPT_WITH_SCOPE" -> "{\"$code\":\"" + random.nextInt(1, 100) + "\",\"$scope\":{}}";
            case "DB_POINTER" -> "{\"$dbPointer\":{\"$ref\":\"sample\",\"$id\":" + objectId(random) + "}}";
            case "UNDEFINED" -> "{\"$undefined\":true}";
            case "MIN_KEY" -> "{\"$minKey\":1}";
            case "MAX_KEY" -> "{\"$maxKey\":1}";
            default -> "null";
        };
    }

    private static String wrappedString(String key, String value) {
        return "{\"" + key + "\":\"" + value + "\"}";
    }

    private static String objectId(SplittableRandom random) {
        return wrappedString("$oid", HEX.formatHex(randomBytes(random, 12)));
    }

    private static byte[] randomBytes(SplittableRandom random, int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }
}
