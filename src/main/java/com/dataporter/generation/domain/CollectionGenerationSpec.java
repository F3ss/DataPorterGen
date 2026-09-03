package com.dataporter.generation.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public record CollectionGenerationSpec(String name, long count, TemplateQuery query, Map<String, GenerationRule> fields) {
    public CollectionGenerationSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("generation collection name is required");
        if (name.startsWith("system.")) throw new IllegalArgumentException("system collections cannot be generated");
        if (count < 0) throw new IllegalArgumentException("generation collection count must not be negative");
        if (query == null) throw new IllegalArgumentException("generation template query is required");
        fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public CollectionGenerationSpec(String name, long count, Map<String, GenerationRule> fields) {
        this(name, count, TemplateQuery.matchAll(), fields);
    }
}
