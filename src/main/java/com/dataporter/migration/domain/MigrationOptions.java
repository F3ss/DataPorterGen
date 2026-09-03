package com.dataporter.migration.domain;

public record MigrationOptions(
        ExistingTargetStrategy existingTargetStrategy,
        ConsistencyMode consistencyMode,
        int batchSize,
        int parallelism,
        boolean verificationEnabled,
        VerificationLevel verificationLevel,
        CollectionSelection collectionSelection,
        boolean continueOnCollectionError,
        RetrySettings retry) {
    public MigrationOptions(ExistingTargetStrategy existingTargetStrategy, ConsistencyMode consistencyMode,
                            int batchSize, int parallelism, boolean verificationEnabled,
                            VerificationLevel verificationLevel, boolean continueOnCollectionError,
                            RetrySettings retry) {
        this(existingTargetStrategy, consistencyMode, batchSize, parallelism, verificationEnabled,
                verificationLevel, CollectionSelection.all(), continueOnCollectionError, retry);
    }
}
