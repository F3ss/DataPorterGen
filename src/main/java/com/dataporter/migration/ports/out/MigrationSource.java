package com.dataporter.migration.ports.out;

public interface MigrationSource extends DatabaseReader {
    void checkReadable();
}
