package com.dataporter.generation.domain;

import com.dataporter.shared.error.ConfigurationException;

public final class GenerationModeValidator {
    public void validate(GenerationTargetMode mode, boolean collectionFiltersPresent) {
        if (collectionFiltersPresent)
            throw new ConfigurationException("include-collections and exclude-collections must be empty in GENERATE mode");
        if (mode == GenerationTargetMode.RECREATE_TARGET)
            throw new ConfigurationException("DROP_AND_RECREATE is forbidden in GENERATE mode; generation only upserts by _id");
        if (mode == GenerationTargetMode.MERGE_TARGET)
            throw new ConfigurationException("MERGE is unsupported in GENERATE mode; generation only upserts by _id");
    }
}
