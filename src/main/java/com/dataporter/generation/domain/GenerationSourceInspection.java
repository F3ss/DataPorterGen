package com.dataporter.generation.domain;

import java.util.List;

public record GenerationSourceInspection(List<String> collections, List<String> views) {
    public GenerationSourceInspection {
        collections = List.copyOf(collections);
        views = List.copyOf(views);
    }
}
