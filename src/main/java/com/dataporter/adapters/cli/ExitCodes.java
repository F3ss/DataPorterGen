package com.dataporter.adapters.cli;

import com.dataporter.generation.domain.GenerationReport;
import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationStatus;

public final class ExitCodes {
    public static final int SUCCESS = 0, CONFIGURATION = 2, SOURCE_CONNECTION = 3, TARGET_CONNECTION = 4,
            TARGET_PREPARATION = 5, MIGRATION = 6, VERIFICATION = 7, CANCELLED = 8;
    private ExitCodes() {}

    public static int from(MigrationReport report) {
        if (report.status() == OperationStatus.SUCCESS) return SUCCESS;
        if (report.status() == OperationStatus.VERIFICATION_FAILED) return VERIFICATION;
        if (report.status() == OperationStatus.CANCELLED) return CANCELLED;
        if (hasFailureKind(report.errors(), FailureKind.CONFIGURATION)) return CONFIGURATION;
        if (hasFailureKind(report.errors(), FailureKind.SOURCE_CONNECTION)) return SOURCE_CONNECTION;
        if (hasFailureKind(report.errors(), FailureKind.TARGET_CONNECTION)) return TARGET_CONNECTION;
        if (hasFailureKind(report.errors(), FailureKind.TARGET_PREPARATION)) return TARGET_PREPARATION;
        return MIGRATION;
    }

    public static int from(GenerationReport report) {
        if (report.status() == OperationStatus.SUCCESS) return SUCCESS;
        if (report.status() == OperationStatus.CANCELLED) return CANCELLED;
        if (hasFailureKind(report.errors(), FailureKind.CONFIGURATION)) return CONFIGURATION;
        if (hasFailureKind(report.errors(), FailureKind.SOURCE_CONNECTION, FailureKind.SOURCE_INSPECTION))
            return SOURCE_CONNECTION;
        if (hasFailureKind(report.errors(), FailureKind.TARGET_CONNECTION, FailureKind.TARGET_PREPARATION))
            return TARGET_CONNECTION;
        return MIGRATION;
    }

    public static int from(RuntimeException error) {
        switch (FailureKind.of(error)) {
            case CONFIGURATION: return CONFIGURATION;
            case SOURCE_CONNECTION: return SOURCE_CONNECTION;
            case TARGET_CONNECTION: return TARGET_CONNECTION;
            case TARGET_PREPARATION: return TARGET_PREPARATION;
            case CANCELLED: return CANCELLED;
            default: return MIGRATION;
        }
    }

    private static boolean hasFailureKind(Iterable<OperationIssue> errors, FailureKind... kinds) {
        for (OperationIssue issue : errors)
            for (FailureKind kind : kinds)
                if (issue.failureKind() == kind) return true;
        return false;
    }
}
