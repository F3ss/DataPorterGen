package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;

import java.util.List;
import java.util.Set;

public interface GenerationTarget extends AutoCloseable {
    void checkConnection();
    void checkWritable();
    Set<String> ordinaryCollections();
    List<UniqueConstraint> uniqueConstraints(String collection);
    long nextSequenceStart(String collection, String jsonPointer, long step);
    boolean constraintKeyConflicts(UniqueConstraint constraint, List<BsonPayload> keys, List<BsonPayload> idKeys);
    void upsert(DataBatch batch);
    @Override void close();
}
