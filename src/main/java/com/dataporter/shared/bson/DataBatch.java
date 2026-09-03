package com.dataporter.shared.bson;

import java.util.List;

public record DataBatch(String collection, List<BsonPayload> documents, long bytes) {
    public DataBatch {
        documents = List.copyOf(documents);
    }
    public boolean isEmpty() { return documents.isEmpty(); }
}
