package com.dataporter.migration.domain;

import java.util.*;

public record CollectionSelection(Set<String> includeCollections, Set<String> excludeCollections) {
    public CollectionSelection {
        includeCollections = normalize(includeCollections);
        excludeCollections = normalize(excludeCollections);
    }

    public static CollectionSelection from(Collection<String> included, Collection<String> excluded) {
        return new CollectionSelection(normalize(included), normalize(excluded));
    }

    public static CollectionSelection all() { return new CollectionSelection(Set.of(), Set.of()); }
    public boolean selectsAll() { return includeCollections.isEmpty() && excludeCollections.isEmpty(); }
    public boolean isIncludeMode() { return !includeCollections.isEmpty(); }
    public boolean isExcludeMode() { return !excludeCollections.isEmpty(); }

    private static Set<String> normalize(Collection<String> names) {
        if (names == null || names.isEmpty()) return Set.of();
        TreeSet<String> normalized = new TreeSet<>();
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) normalized.add(name.trim());
        }
        return Collections.unmodifiableSet(normalized);
    }
}
