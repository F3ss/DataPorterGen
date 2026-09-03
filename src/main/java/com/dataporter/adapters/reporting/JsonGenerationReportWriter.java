package com.dataporter.adapters.reporting;

import com.dataporter.generation.domain.GenerationReport;
import com.dataporter.generation.ports.out.GenerationReportWriter;
import com.dataporter.shared.error.ConfigurationException;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.*;

public final class JsonGenerationReportWriter implements GenerationReportWriter {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
    public JsonGenerationReportWriter(Path path) { this.path = path.toAbsolutePath().normalize(); }
    @Override public void prepare() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                Files.deleteIfExists(Files.createTempFile(parent, "dataportergen-report-probe-", ".tmp"));
            }
        } catch (IOException | RuntimeException e) {
            throw new ConfigurationException("Cannot write generation report to " + path, e);
        }
    }
    @Override public void write(GenerationReport report) {
        try {
            Path parent = path.getParent(); if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writeValue(temporary.toFile(), report);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot write generation report to " + path, e); }
    }
}
