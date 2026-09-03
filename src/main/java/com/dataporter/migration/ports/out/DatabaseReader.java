package com.dataporter.migration.ports.out;

import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.ports.out.BatchCursor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DatabaseReader extends AutoCloseable {
    void checkConnection();
    boolean databaseExists();
    MigrationPlan inspect();
    BatchCursor openBatches(String collection, int batchSize);
    long count(String collection);
    default List<BsonPayload> findManyBySourceDocuments(String collection, List<BsonPayload> sourceDocuments) {
        throw new UnsupportedOperationException("Document lookup by _id is not implemented");
    }
    /** Resolved server addresses of the connected cluster; empty when no topology evidence is available. */
    default Optional<Set<String>> clusterHosts() { return Optional.empty(); }
    @Override void close();
}
