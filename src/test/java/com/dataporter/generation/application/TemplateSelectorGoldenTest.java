package com.dataporter.generation.application;

import com.dataporter.generation.domain.TemplateSelection;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins the exact ordinal sequences produced by the template selector for both strategies, several
 * seeds and collections, and template counts including 1, primes, and counts above 2^31. Performance
 * work (parameter memoization, integer math) must keep this digest unchanged. To re-baseline after
 * an intentional contract change, set the constant to PIN-ME, run the test, and copy the digest.
 */
class TemplateSelectorGoldenTest {
    private static final String GOLDEN_SHA256 = "2a6103a6a3de8f66f2ae9d82917893fc2c87c494df432cb5edcc02f5d90147ac";

    @Test void selectionIsByteStableAcrossStrategiesSeedsCollectionsAndCounts() {
        TemplateSelector selector = new TemplateSelector();
        MessageDigest sha = sha256();
        long[] counts = { 1, 2, 3, 17, 1000, 1_000_003, 4_611_686_018_427_387_904L };
        long[] seeds = { 0, 123, -42, Long.MIN_VALUE + 1 };
        String[] collections = { "a", "beta", "order-items" };
        int step = 0;
        for (TemplateSelection strategy : TemplateSelection.values())
            for (long seed : seeds)
                for (String collection : collections)
                    for (long count : counts)
                        for (long iteration = 0; iteration < 1_000; iteration++)
                            absorb(sha, selector.select(strategy, seed, collection,
                                    iteration * 7 + (step++ % 5), count));
        String actual = hex(sha.digest());
        if (GOLDEN_SHA256.equals("PIN-ME")) fail("Unpinned golden digest; set GOLDEN_SHA256 to: " + actual);
        assertThat(actual).isEqualTo(GOLDEN_SHA256);
    }

    private static void absorb(MessageDigest sha, long selected) {
        sha.update(new byte[] { (byte) (selected >>> 56), (byte) (selected >>> 48), (byte) (selected >>> 40),
                (byte) (selected >>> 32), (byte) (selected >>> 24), (byte) (selected >>> 16),
                (byte) (selected >>> 8), (byte) selected });
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
}
