package com.dataporter.migration.ports.out;

import com.dataporter.migration.domain.MigrationReport;

@FunctionalInterface
public interface MigrationReportWriter {
    void write(MigrationReport report);
    default void prepare() {}
}
