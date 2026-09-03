package com.dataporter.migration.domain;

import java.util.List;

public record MigrationPlan(List<CollectionDefinition> collections, List<IndexDefinition> indexes,
                            List<ViewDefinition> views) {
    public MigrationPlan {
        collections = List.copyOf(collections);
        indexes = List.copyOf(indexes);
        views = List.copyOf(views);
    }
}
