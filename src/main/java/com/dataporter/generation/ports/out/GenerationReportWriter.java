package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.GenerationReport;

@FunctionalInterface
public interface GenerationReportWriter {
    void write(GenerationReport report);
    default void prepare() {}
}
