package com.dataporter.adapters.reporting;

import com.dataporter.generation.domain.GenerationCollectionResult;
import com.dataporter.generation.domain.GenerationReport;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.TemplateSelection;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.domain.OperationMode;
import com.dataporter.shared.domain.OperationStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class JsonGenerationReportWriterTest {
    @Test void reportContainsOperationalFactsButNoCredentialsOrGeneratedValues(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("generation.json");
        GenerationReport report = new GenerationReport(OperationMode.GENERATE, "id", OperationStatus.FAILED,
                42, TemplateSelection.SHUFFLED_CYCLE, false, true,
                "mongodb://alice:secret@source:27017/?token=hidden", "mongodb://bob:secret@target:27017",
                "source", "target", "abcdef", 2, 100, 10, 2, Instant.EPOCH, Instant.EPOCH,
                Map.of("GENERATE_AND_APPEND", 1L), List.of(new GenerationCollectionResult("items", 3, 2, 1,
                2, 20, true, 30, ResolvedIdStrategy.explicit(), ObjectStatus.PARTIAL, "password=value")),
                List.of("mongodb://user:pass@host:27017"), List.of(), false);

        new JsonGenerationReportWriter(output).write(report);
        String json = Files.readString(output);
        assertThat(json).contains("GENERATE", "\"status\" : \"FAILED\"", "\"errors\" : [",
                        "configHash", "abcdef", "safeToRetry", "password=***",
                        "\"allowUnprovenIds\" : true",
                        "\"templateSelection\" : \"SHUFFLED_CYCLE\"",
                        "\"snapshotTemplates\" : 2", "\"snapshotBytes\" : 20", "\"snapshotTruncated\" : true")
                .doesNotContain("alice", "bob", "secret", "hidden", "user:pass", "generated-document-value");
    }

    @Test void prepareFailsFastWhenReportPathIsNotWritable(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("blocking"), "not a directory");
        JsonGenerationReportWriter writer = new JsonGenerationReportWriter(temp.resolve("blocking").resolve("report.json"));

        org.assertj.core.api.Assertions.assertThatThrownBy(writer::prepare)
                .isInstanceOf(com.dataporter.shared.error.ConfigurationException.class)
                .hasMessageContaining("report");
    }
}
