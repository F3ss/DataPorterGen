package com.dataporter.generation.domain;

public record GenerationOptions(boolean validateOnly, boolean allowUnprovenIds,
                                boolean validateFieldFormats, boolean updateFieldFormats) {
    public GenerationOptions(boolean validateOnly, boolean allowUnprovenIds) {
        this(validateOnly, allowUnprovenIds, true, false);
    }
}
