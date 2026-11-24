package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.GenerationFormatChange;
import com.dataporter.shared.error.ConfigurationException;

import java.util.List;
import java.util.function.Consumer;

@FunctionalInterface
public interface GenerationSpecReader {
    GenerationSpec read();

    /** Validate the candidate before replacing the persisted configuration. */
    default GenerationSpec updateFieldFormats(String originalHash, List<GenerationFormatChange> changes,
                                              Consumer<GenerationSpec> validate) {
        throw new ConfigurationException("Generation config does not support updating field formats");
    }
}
