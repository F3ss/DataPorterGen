package com.dataporter.adapters.reporting;

import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.migration.ports.out.MigrationReportWriter;
import com.dataporter.shared.error.ConfigurationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.*;

public final class JsonReportWriter implements MigrationReportWriter {
    private final Path path;
    private final ObjectMapper mapper;

    public JsonReportWriter(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override public void prepare() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                Files.deleteIfExists(Files.createTempFile(parent, "dataportergen-report-probe-", ".tmp"));
            }
        } catch (IOException | RuntimeException e) {
            throw new ConfigurationException("Cannot write migration report to " + path, e);
        }
    }

    public void write(MigrationReport report) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writeValue(temporary.toFile(), report);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write migration report to " + path, e);
        }
    }
}
