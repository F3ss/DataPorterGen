package com.dataporter.migration.ports.out;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.migration.domain.merge.MergeBatchResult;
import com.dataporter.migration.domain.merge.MergePreflightResult;
import com.dataporter.shared.bson.DataBatch;

public interface MigrationTarget extends DatabaseReader {
    void checkWritable();
    boolean hasUserObjects();
    void dropDatabase();
    void createCollection(CollectionDefinition definition);
    void writeBatch(DataBatch batch);
    default MergePreflightResult preflightMerge(MigrationPlan sourcePlan) {
        throw new UnsupportedOperationException("MERGE preflight is not implemented");
    }
    default MergeBatchResult mergeBatch(DataBatch batch) {
        throw new UnsupportedOperationException("MERGE document handling is not implemented");
    }
    void createIndex(IndexDefinition definition);
    void createView(ViewDefinition definition);
}
