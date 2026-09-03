package com.dataporter.generation.domain;

import com.dataporter.generation.domain.GenerationRule.DateSource;
import com.dataporter.generation.domain.GenerationRule.FixedDate;
import com.dataporter.generation.domain.GenerationRule.RandomDateRange;

import java.util.Objects;

public record SharedDateDefinition(DateSource source) {
    public SharedDateDefinition {
        Objects.requireNonNull(source, "shared date source is required");
        if (!(source instanceof FixedDate) && !(source instanceof RandomDateRange))
            throw new IllegalArgumentException("shared date supports only fixed or randomRange sources");
    }
}
