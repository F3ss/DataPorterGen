package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.GenerationSourceInspection;
import com.dataporter.generation.domain.TemplateQuery;
import com.dataporter.shared.ports.out.BatchCursor;

public interface GenerationSource extends AutoCloseable {
    void checkConnection();
    void checkReadable();
    boolean databaseExists();
    GenerationSourceInspection inspect();
    BatchCursor openBatches(String collection, int batchSize);
    default BatchCursor openTemplateBatches(String collection, TemplateQuery query, int batchSize) {
        if (!query.isMatchAll())
            throw new UnsupportedOperationException("Filtered generation template reads are not implemented");
        return openBatches(collection, batchSize);
    }
    @Override void close();
}
