package com.dataporter.migration.domain;

import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.security.SecretMasker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MigrationReport(
        String migrationId, OperationStatus status, String source, String target,
        String sourceDatabase, String targetDatabase, ExistingTargetStrategy targetStrategy,
        ConsistencyMode consistencyStrategy, VerificationLevel verificationLevel,
        CollectionSelection collectionSelection,
        Instant startedAt, Instant finishedAt, Map<String, Long> stageDurationsMillis,
        List<ObjectResult> objects, List<String> warnings, List<OperationIssue> errors,
        VerificationResult verification, boolean safeToRetry) {
    public MigrationReport {
        source = SecretMasker.sanitize(source);
        target = SecretMasker.sanitize(target);
        stageDurationsMillis = Map.copyOf(stageDurationsMillis);
        objects = List.copyOf(objects);
        warnings = warnings.stream().map(SecretMasker::redact).toList();
        errors = List.copyOf(errors);
    }
    public MigrationReport(String migrationId, OperationStatus status, String source, String target,
                           String sourceDatabase, String targetDatabase, ExistingTargetStrategy targetStrategy,
                           ConsistencyMode consistencyStrategy, VerificationLevel verificationLevel,
                           Instant startedAt, Instant finishedAt, Map<String, Long> stageDurationsMillis,
                           List<ObjectResult> objects, List<String> warnings, List<OperationIssue> errors,
                           VerificationResult verification, boolean safeToRetry) {
        this(migrationId, status, source, target, sourceDatabase, targetDatabase, targetStrategy,
                consistencyStrategy, verificationLevel, CollectionSelection.all(), startedAt, finishedAt,
                stageDurationsMillis, objects, warnings, errors, verification, safeToRetry);
    }
    public boolean successful() { return status == OperationStatus.SUCCESS && verification.successful(); }
    public long durationMillis() { return Duration.between(startedAt, finishedAt).toMillis(); }
}
