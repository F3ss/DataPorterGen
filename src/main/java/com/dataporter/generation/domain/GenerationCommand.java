package com.dataporter.generation.domain;

import com.dataporter.shared.domain.Endpoint;

public record GenerationCommand(Endpoint source, Endpoint target, GenerationOptions options) {
    public GenerationCommand {
        if (source == null || target == null || options == null)
            throw new IllegalArgumentException("generation command fields are required");
    }
}
