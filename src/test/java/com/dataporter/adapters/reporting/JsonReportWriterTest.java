package com.dataporter.adapters.reporting;

import com.dataporter.migration.domain.CollectionSelection;
import com.dataporter.migration.domain.ConsistencyMode;
import com.dataporter.migration.domain.ExistingTargetStrategy;
import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.migration.domain.ObjectResult;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.migration.domain.VerificationResult;
import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReportWriterTest {
    @Test
    void prepareAcceptsWritablePathAndLeavesNoResidue(@TempDir Path directory) {
        Path output = directory.resolve("nested").resolve("report.json");
        org.assertj.core.api.Assertions.assertThatCode(() -> new JsonReportWriter(output).prepare()).doesNotThrowAnyException();
        assertThat(java.util.stream.Stream.ofNullable(output.getParent().toFile().list()))
                .allSatisfy(names -> assertThat(names).isEmpty());
    }

    @Test
    void prepareFailsFastWhenReportPathIsNotWritable(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("blocking"), "not a directory");
        JsonReportWriter writer = new JsonReportWriter(directory.resolve("blocking").resolve("report.json"));

        org.assertj.core.api.Assertions.assertThatThrownBy(writer::prepare)
                .isInstanceOf(com.dataporter.shared.error.ConfigurationException.class)
                .hasMessageContaining("report");
    }

    @Test
    void persistedReportContainsNoCredentials(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("report.json");
        var report = new MigrationReport("id", OperationStatus.FAILED,
                "mongodb://source-user:source-password@source:27017",
                "mongodb://target-user:target-password@target:27017", "a", "b",
                ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, VerificationLevel.FULL,
                CollectionSelection.from(List.of("customers", "orders"), List.of()),
                Instant.EPOCH, Instant.EPOCH, Map.of(), List.of(
                        ObjectResult.mergeCollection("customers", ObjectStatus.COMPLETE,
                                3, 120, 1, 1, 2, "merged")),
                List.of("failed at mongodb://hidden:secret@host:27017/db"),
                List.of(new OperationIssue("COPY", "c", "password=very-secret", FailureKind.DOCUMENT)),
                new VerificationResult(false, List.of()), true);

        new JsonReportWriter(output).write(report);

        assertThat(Files.readString(output)).doesNotContain("source-password", "target-password", "very-secret", "hidden", "secret@")
                .contains("\"status\" : \"FAILED\"", "\"errors\" : [", "mongodb://source:27017",
                        "password=***", "collectionSelection", "includeCollections",
                        "customers", "orders", "\"sourceDocuments\" : 3", "\"insertedDocuments\" : 1",
                        "\"replacedDocuments\" : 1", "\"conflicts\" : 2",
                        "\"failureKind\" : \"DOCUMENT\"");
    }
}
