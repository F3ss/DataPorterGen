package com.dataporter.generation.domain;

import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationMode;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.security.SecretMasker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GenerationReport(OperationMode operationMode, String generationId, OperationStatus status,
                               long seed, TemplateSelection templateSelection, boolean validateOnly,
                               boolean allowUnprovenIds, String source, String target,
                               String sourceDatabase, String targetDatabase, String configHash,
                               int parallelism, int batchSize, long maxWorkingMegabytes,
                               long maxInFlightMegabytes, Instant startedAt, Instant finishedAt,
                               Map<String, Long> stageDurationsMillis,
                               List<GenerationCollectionResult> collections,
                               List<String> warnings, List<OperationIssue> errors, boolean safeToRetry) {
    public GenerationReport {
        operationMode = OperationMode.GENERATE;
        source = SecretMasker.sanitize(source);
        target = SecretMasker.sanitize(target);
        configHash = configHash == null ? "" : configHash;
        stageDurationsMillis = Map.copyOf(stageDurationsMillis);
        collections = List.copyOf(collections);
        warnings = warnings.stream().map(SecretMasker::redact).toList();
        errors = List.copyOf(errors);
    }
    public long durationMillis() { return Duration.between(startedAt, finishedAt).toMillis(); }
    public boolean successful() { return status == OperationStatus.SUCCESS; }
}
