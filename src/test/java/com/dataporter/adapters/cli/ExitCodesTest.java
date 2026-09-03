package com.dataporter.adapters.cli;

import com.dataporter.migration.domain.ConsistencyMode;
import com.dataporter.migration.domain.ExistingTargetStrategy;
import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.migration.domain.VerificationResult;
import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.error.ConfigurationException;
import com.dataporter.shared.error.TargetPreparationException;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ExitCodesTest {
    @Test
    void mapsEveryTerminalStatusToDocumentedCode() {
        assertThat(ExitCodes.from(report(OperationStatus.SUCCESS, List.of()))).isZero();
        assertThat(ExitCodes.from(report(OperationStatus.VERIFICATION_FAILED, List.of()))).isEqualTo(7);
        assertThat(ExitCodes.from(report(OperationStatus.CANCELLED, List.of()))).isEqualTo(8);
        assertThat(ExitCodes.from(report(OperationStatus.FAILED,
                List.of(new OperationIssue("CONNECT", "", "down", FailureKind.SOURCE_CONNECTION))))).isEqualTo(3);
        assertThat(ExitCodes.from(report(OperationStatus.FAILED,
                List.of(new OperationIssue("VALIDATE_TARGET", "", "exists", FailureKind.TARGET_PREPARATION))))).isEqualTo(5);
    }

    @Test
    void typedFailureKindsDriveExitCodesRegardlessOfMessageText() {
        assertThat(ExitCodes.from(report(OperationStatus.FAILED,
                List.of(new OperationIssue("VALIDATE_CONFIGURATION", "", "rejected", FailureKind.CONFIGURATION))))).isEqualTo(2);
        assertThat(ExitCodes.from(report(OperationStatus.FAILED,
                List.of(new OperationIssue("CONNECT_TARGET", "", "unreachable", FailureKind.TARGET_CONNECTION))))).isEqualTo(4);
        assertThat(ExitCodes.from(report(OperationStatus.FAILED,
                List.of(new OperationIssue("INSPECT_SOURCE", "", "missing", FailureKind.SOURCE_INSPECTION))))).isEqualTo(6);
        assertThat(ExitCodes.from(report(OperationStatus.FAILED,
                List.of(new OperationIssue("COPY_DOCUMENTS", "", "write failed", FailureKind.UNKNOWN))))).isEqualTo(6);
    }

    @Test
    void messageTextAloneNoLongerDrivesExitCodes() {
        assertThat(ExitCodes.from(report(OperationStatus.FAILED,
                List.of(new OperationIssue("COPY_DOCUMENTS", "c",
                        "TargetExceptionNameInText: mentioned ConfigurationException", FailureKind.UNKNOWN)))))
                .isEqualTo(6);
    }

    @Test
    void configurationKindWinsOverLaterTargetKinds() {
        assertThat(ExitCodes.from(report(OperationStatus.FAILED, List.of(
                new OperationIssue("PREPARE_TARGET", "", "failed", FailureKind.TARGET_PREPARATION),
                new OperationIssue("VALIDATE_CONFIGURATION", "", "rejected", FailureKind.CONFIGURATION)))))
                .isEqualTo(2);
    }

    @Test
    void classifiesEscapedExceptionsByTypedKind() {
        assertThat(ExitCodes.from((RuntimeException) new ConfigurationException("bad")))
                .isEqualTo(ExitCodes.CONFIGURATION);
        assertThat(ExitCodes.from(new TargetPreparationException("conflict", new IllegalStateException("cause"))))
                .isEqualTo(ExitCodes.TARGET_PREPARATION);
        assertThat(ExitCodes.from(new IllegalArgumentException("binding"))).isEqualTo(ExitCodes.CONFIGURATION);
    }

    private MigrationReport report(OperationStatus status, List<OperationIssue> errors) {
        return new MigrationReport("id", status, "mongodb://s", "mongodb://t", "a", "b",
                ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, VerificationLevel.METADATA_AND_COUNTS,
                Instant.EPOCH, Instant.EPOCH, Map.of(), List.of(), List.of(), errors,
                new VerificationResult(status != OperationStatus.VERIFICATION_FAILED, List.of()), true);
    }
}
