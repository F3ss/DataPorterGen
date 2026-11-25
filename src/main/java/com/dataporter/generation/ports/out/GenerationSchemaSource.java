package com.dataporter.generation.ports.out;

import com.dataporter.shared.ports.out.CancellationToken;

import java.util.Map;
import java.util.Set;

public interface GenerationSchemaSource extends AutoCloseable {
    void checkConnection();

    FieldSchema inspectFields(String collection, Set<String> configuredPaths, int batchSize, CancellationToken cancellation);

    @Override void close();

    record FieldSchema(Map<String, Set<String>> fields, Set<String> presentPaths, boolean absenceKnown) {}
}
