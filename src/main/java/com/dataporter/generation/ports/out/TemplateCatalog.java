package com.dataporter.generation.ports.out;

import com.dataporter.shared.bson.BsonPayload;

public interface TemplateCatalog extends AutoCloseable {
    long count(String collection);
    long bytes(String collection);
    boolean truncated(String collection);
    BsonPayload get(String collection, long ordinal);
    @Override void close();
}
