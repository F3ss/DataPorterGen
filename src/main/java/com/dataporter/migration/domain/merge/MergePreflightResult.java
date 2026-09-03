package com.dataporter.migration.domain.merge;

import com.dataporter.migration.domain.IndexDefinition;

import java.util.List;
import java.util.Map;

public record MergePreflightResult(
        List<String> collectionsToCreate,
        List<IndexDefinition> indexesToCreate,
        List<String> viewsToCreate,
        Map<String, Long> initialDocumentCounts) {
    public MergePreflightResult {
        collectionsToCreate = List.copyOf(collectionsToCreate);
        indexesToCreate = List.copyOf(indexesToCreate);
        viewsToCreate = List.copyOf(viewsToCreate);
        initialDocumentCounts = Map.copyOf(initialDocumentCounts);
    }

    public boolean createsCollection(String name) { return collectionsToCreate.contains(name); }
    public boolean createsIndex(IndexDefinition index) { return indexesToCreate.contains(index); }
    public boolean createsView(String name) { return viewsToCreate.contains(name); }
}
