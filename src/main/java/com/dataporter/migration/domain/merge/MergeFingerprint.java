package com.dataporter.migration.domain.merge;

import com.dataporter.shared.bson.BsonPayload;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class MergeFingerprint {
    private MergeFingerprint() {}

    public static String batch(List<BsonPayload> documents) {
        MessageDigest digest = sha256();
        for (BsonPayload document : documents) {
            byte[] bytes = document.bytes();
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return hex(digest.digest());
    }

    public static Accumulator accumulator() { return new Accumulator(); }

    public static final class Accumulator {
        private final MessageDigest digest = sha256();

        public void addBatch(String batchFingerprint) {
            if (batchFingerprint == null || batchFingerprint.length() != 64)
                throw new IllegalArgumentException("Invalid MERGE batch fingerprint");
            digest.update(java.util.HexFormat.of().parseHex(batchFingerprint));
        }

        public String finish() { return hex(digest.digest()); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static String hex(byte[] value) { return java.util.HexFormat.of().formatHex(value); }
}
