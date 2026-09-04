package com.dataporter.generation.domain;

public record GenerationOptions(boolean validateOnly, boolean allowUnprovenIds, boolean onlyConfiguredFields) {
    public GenerationOptions(boolean validateOnly, boolean allowUnprovenIds) { this(validateOnly, allowUnprovenIds, false); }
}
