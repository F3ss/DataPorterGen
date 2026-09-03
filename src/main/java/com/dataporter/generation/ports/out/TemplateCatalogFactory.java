package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.CollectionGenerationSpec;

import java.util.List;

@FunctionalInterface
public interface TemplateCatalogFactory {
    TemplateCatalog snapshot(GenerationSource source, List<CollectionGenerationSpec> collections, long maxBytes);
}
