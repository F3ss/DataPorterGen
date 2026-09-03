package com.dataporter.migration.domain.merge;

public record MergeBatchResult(long inserted, long replaced, String expectedTargetFingerprint) {
    public MergeBatchResult {
        if (inserted < 0 || replaced < 0) throw new IllegalArgumentException("MERGE batch counters must not be negative");
        expectedTargetFingerprint = expectedTargetFingerprint == null ? "" : expectedTargetFingerprint;
    }

    public MergeBatchResult(long inserted, long replaced) {
        this(inserted, replaced, "");
    }
    public long processed() { return inserted + replaced; }
}
