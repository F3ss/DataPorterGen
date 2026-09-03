package com.dataporter.migration.domain.merge;

import java.util.Map;

public record MergeVerificationContext(Map<String, MergeCollectionSummary> collections) {
    public MergeVerificationContext {
        collections = Map.copyOf(collections);
    }
}
