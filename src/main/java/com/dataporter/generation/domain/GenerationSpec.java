package com.dataporter.generation.domain;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record GenerationSpec(int version, Long seed, TemplateSelection templateSelection,
                             int batchSize, int parallelism,
                             long maxWorkingMegabytes, long maxInFlightMegabytes,
                             Map<String, SharedDateDefinition> sharedDates,
                             List<CollectionGenerationSpec> collections, String configHash) {
    public GenerationSpec {
        if (version != 1) throw new IllegalArgumentException("generation config version must be 1");
        if (templateSelection == null) throw new IllegalArgumentException("templateSelection is required");
        if (batchSize < 1 || batchSize > 100_000) throw new IllegalArgumentException("batchSize must be in [1,100000]");
        if (parallelism < 1 || parallelism > 64) throw new IllegalArgumentException("parallelism must be in [1,64]");
        if (maxWorkingMegabytes < 1 || maxInFlightMegabytes < 1)
            throw new IllegalArgumentException("generation memory limits must be positive MiB values");
        if (maxWorkingMegabytes > Long.MAX_VALUE / (1024L * 1024)
                || maxInFlightMegabytes > Integer.MAX_VALUE / 1024L)
            throw new IllegalArgumentException("generation memory limits are too large");
        if (sharedDates == null) throw new IllegalArgumentException("sharedDates are required");
        LinkedHashMap<String, SharedDateDefinition> copiedDates = new LinkedHashMap<>();
        sharedDates.forEach((name, definition) -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("shared date name is required");
            copiedDates.put(name, java.util.Objects.requireNonNull(definition, "shared date definition is required"));
        });
        sharedDates = java.util.Collections.unmodifiableMap(copiedDates);
        collections = List.copyOf(collections);
        if (collections.isEmpty()) throw new IllegalArgumentException("generation collections must not be empty");
    }

    public GenerationSpec(int version, Long seed, TemplateSelection templateSelection,
                          int batchSize, int parallelism,
                          long maxWorkingMegabytes, long maxInFlightMegabytes,
                          List<CollectionGenerationSpec> collections, String configHash) {
        this(version, seed, templateSelection, batchSize, parallelism, maxWorkingMegabytes,
                maxInFlightMegabytes, Map.of(), collections, configHash);
    }
}
