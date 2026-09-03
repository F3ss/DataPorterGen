package com.dataporter.migration.domain.merge;

public record MergeCollectionSummary(
        long initialTargetDocuments,
        long sourceDocuments,
        long insertedDocuments,
        long replacedDocuments,
        long conflicts,
        long processedBsonBytes,
        String expectedTargetFingerprint) {
    public MergeCollectionSummary(long initialTargetDocuments, long sourceDocuments,
                                  long insertedDocuments, long replacedDocuments,
                                  long conflicts, long processedBsonBytes) {
        this(initialTargetDocuments, sourceDocuments, insertedDocuments, replacedDocuments,
                conflicts, processedBsonBytes, "");
    }
}
