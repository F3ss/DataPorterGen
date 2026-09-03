package com.dataporter.generation.domain;

import java.util.Set;

public record TemplateFacts(IdKind idKind, Set<String> scalarPathsEqualToId) {
    public enum IdKind { OBJECT_ID, UUID, INT32, INT64, STRING, OTHER, MISSING }
    public TemplateFacts { scalarPathsEqualToId = Set.copyOf(scalarPathsEqualToId); }
}
