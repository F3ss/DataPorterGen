package com.dataporter.generation.domain;

import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.domain.WriteConcerns;
import com.dataporter.shared.error.ConfigurationException;

import java.util.*;

public final class GenerationConfigurationValidator {
    public void validate(GenerationCommand command) {
        List<String> errors = new ArrayList<>();
        if (command == null || command.source() == null || command.target() == null || command.options() == null)
            throw new ConfigurationException("generation configuration is required");
        endpoint("source", command.source(), errors);
        endpoint("target", command.target(), errors);
        if (WriteConcerns.unacknowledged(command.target().uri()))
            errors.add("target URI must use an acknowledged write concern; w=0 is not supported");
        if (!errors.isEmpty()) throw new ConfigurationException(String.join("; ", errors));
    }
    private void endpoint(String name, Endpoint endpoint, List<String> errors) {
        if (endpoint.uri().isBlank()) errors.add(name + " URI must not be blank");
        if (!endpoint.uri().startsWith("mongodb://") && !endpoint.uri().startsWith("mongodb+srv://"))
            errors.add(name + " URI must use mongodb scheme");
        if (endpoint.database().isBlank() || endpoint.database().contains(" ") || endpoint.database().contains("/"))
            errors.add(name + " database name is invalid");
        if (List.of("admin", "config", "local").contains(endpoint.database())) errors.add(name + " system database is not supported");
    }
}
