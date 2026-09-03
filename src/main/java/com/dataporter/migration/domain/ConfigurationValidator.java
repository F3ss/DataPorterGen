package com.dataporter.migration.domain;

import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.domain.WriteConcerns;
import com.dataporter.shared.error.ConfigurationException;

import java.util.ArrayList;
import java.util.List;

public final class ConfigurationValidator {
    public void validate(MigrationCommand command) {
        List<String> errors = new ArrayList<>();
        if (command == null || command.source() == null || command.target() == null || command.options() == null) {
            throw new ConfigurationException("migration configuration is required");
        }
        validateEndpoint("source", command.source(), errors);
        validateEndpoint("target", command.target(), errors);
        if (WriteConcerns.unacknowledged(command.target().uri()))
            errors.add("target URI must use an acknowledged write concern; w=0 is not supported");
        var options = command.options();
        if (options.batchSize() < 1 || options.batchSize() > 100_000) errors.add("batchSize must be between 1 and 100000");
        if (options.parallelism() < 1 || options.parallelism() > 64) errors.add("parallelism must be between 1 and 64");
        if (options.retry() == null || options.retry().maxAttempts() < 1) errors.add("retry.maxAttempts must be positive");
        if (options.retry() != null && (options.retry().initialDelayMillis() < 0 ||
                options.retry().maxDelayMillis() < options.retry().initialDelayMillis())) {
            errors.add("retry delays are invalid");
        }
        if (options.collectionSelection() == null) errors.add("collection selection must not be null");
        else if (options.collectionSelection().isIncludeMode() && options.collectionSelection().isExcludeMode())
            errors.add("include-collections and exclude-collections cannot be used together; clear one of them");
        if (!command.source().uri().isBlank() && !command.target().uri().isBlank()
                && command.source().database().equals(command.target().database())
                && EndpointNormalizer.clusterHosts(command.source().uri()).equals(EndpointNormalizer.clusterHosts(command.target().uri()))) {
            errors.add("source and target resolve to the same cluster and database");
        }
        if (!errors.isEmpty()) throw new ConfigurationException(String.join("; ", errors));
    }

    private void validateEndpoint(String name, Endpoint endpoint, List<String> errors) {
        if (endpoint.uri().isBlank()) errors.add(name + " URI must not be blank");
        if (!endpoint.uri().startsWith("mongodb://") && !endpoint.uri().startsWith("mongodb+srv://"))
            errors.add(name + " URI must use mongodb scheme");
        if (endpoint.database().isBlank()) errors.add(name + " database must not be blank");
        if (endpoint.database().contains(" ") || endpoint.database().contains("/")) errors.add(name + " database name is invalid");
        if (List.of("admin", "config", "local").contains(endpoint.database())) errors.add(name + " system database is not supported");
    }
}
