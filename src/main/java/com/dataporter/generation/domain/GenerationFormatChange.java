package com.dataporter.generation.domain;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Location of a rule in the configuration tree and the properties to update on it. */
public record GenerationFormatChange(String rulePointer, Map<String, String> properties) {
    public GenerationFormatChange {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
