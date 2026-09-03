package com.dataporter.migration.ports.out;

import com.dataporter.migration.domain.MigrationReport;

public interface MigrationProgressReporter {
    void stageStarted(String migrationId, String stage);
    void collectionProgress(String migrationId, String collection, long documents, long bytes);
    default void indexStarted(String migrationId, String collection, String index, int ordinal, int total) {}
    void completed(MigrationReport report);

    static MigrationProgressReporter noop() {
        return new MigrationProgressReporter() {
            public void stageStarted(String migrationId, String stage) {}
            public void collectionProgress(String migrationId, String collection, long documents, long bytes) {}
            public void completed(MigrationReport report) {}
        };
    }
}
