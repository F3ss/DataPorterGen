package com.dataporter.generation.application;

import com.dataporter.generation.domain.GenerationCollectionResult;
import com.dataporter.generation.domain.GenerationCommand;
import com.dataporter.generation.domain.GenerationConfigurationValidator;
import com.dataporter.generation.domain.GenerationReport;
import com.dataporter.generation.domain.GenerationSourceInspection;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.ports.out.GenerationBsonEngine;
import com.dataporter.generation.ports.out.GenerationProgressReporter;
import com.dataporter.generation.ports.out.GenerationReportWriter;
import com.dataporter.generation.ports.out.GenerationSource;
import com.dataporter.generation.ports.out.GenerationSpecReader;
import com.dataporter.generation.ports.out.GenerationTarget;
import com.dataporter.generation.ports.out.TemplateCatalog;
import com.dataporter.generation.ports.out.TemplateCatalogFactory;
import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationMode;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.error.OperationCancelledException;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.ports.out.CancellationToken;
import com.dataporter.shared.security.SecretMasker;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GenerationOrchestrator {
    public static final List<String> STAGES = List.of("VALIDATE_CONFIGURATION", "CONNECT_SOURCE", "INSPECT_SOURCE",
            "VALIDATE_COLLECTION_ORDER", "SNAPSHOT_TEMPLATES", "CONNECT_TARGET", "VALIDATE_TARGET_COLLECTIONS",
            "RESOLVE_IDS_AND_UNIQUE_CONSTRAINTS", "VALIDATE_GENERATION_RULES", "CHECK_TARGET_WRITABLE",
            "GENERATE_AND_APPEND", "WRITE_REPORT");

    private final GenerationSource source;
    private final GenerationTarget target;
    private final GenerationSpecReader specReader;
    private final TemplateCatalogFactory catalogs;
    private final GenerationBsonEngine bson;
    private final GenerationReportWriter reports;
    private final GenerationProgressReporter progress;
    private final CancellationToken cancellation;
    private final GenerationConfigurationValidator commandValidator = new GenerationConfigurationValidator();
    private final GenerationSpecValidator specValidator = new GenerationSpecValidator();
    private final GenerationPreflight preflight;
    private final GenerationBatchExecutor executor;

    public GenerationOrchestrator(GenerationSource source, GenerationTarget target, GenerationSpecReader specReader,
                                  TemplateCatalogFactory catalogs, GenerationBsonEngine bson,
                                  GenerationReportWriter reports, CancellationToken cancellation) {
        this(source, target, specReader, catalogs, bson, reports, GenerationProgressReporter.noop(), cancellation);
    }

    public GenerationOrchestrator(GenerationSource source, GenerationTarget target, GenerationSpecReader specReader,
                                  TemplateCatalogFactory catalogs, GenerationBsonEngine bson,
                                  GenerationReportWriter reports, GenerationProgressReporter progress,
                                  CancellationToken cancellation) {
        this.source = source; this.target = target; this.specReader = specReader; this.catalogs = catalogs;
        this.bson = bson; this.reports = reports; this.progress = progress; this.cancellation = cancellation;
        this.preflight = new GenerationPreflight(target, bson, progress, cancellation);
        this.executor = new GenerationBatchExecutor(target, bson, progress, cancellation);
    }

    public GenerationReport generate(GenerationCommand command) {
        String id = UUID.randomUUID().toString(); Instant started = Instant.now();
        Map<String,Long> durations = new LinkedHashMap<>(); List<OperationIssue> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>(List.of(
                "Generation uses BASIC consistency and is not atomic; concurrent source or target writes can affect the result",
                "Generation has no rollback or resume; an interrupted upsert may require manual cleanup"));
        List<String> preWriteWarnings = new ArrayList<>();
        if (command.options().allowUnprovenIds()) {
            String warning = "ID UNIQUENESS PROOF DISABLED: migration.generation.allow-unproven-ids=true; "
                    + "repeated _id values use exact-id upsert-replace and final collection growth may be less than count";
            warnings.add(warning);
            preWriteWarnings.add(warning);
        }
        GenerationSpec[] spec = {null}; long[] seed = {0}; GenerationSourceInspection[] sourcePlan = {null};
        TemplateCatalog[] catalog = {null}; Map<String,ResolvedIdStrategy> ids = new LinkedHashMap<>();
        Map<String,GenerationPreflight.RandomStringId> randomStringIds = new LinkedHashMap<>();
        Map<String,IdRandomnessAnalyzer.Analysis> randomAnalyses = new LinkedHashMap<>();
        Map<String,List<com.dataporter.generation.domain.UniqueConstraint>> constraints = new LinkedHashMap<>();
        Map<String,Long> sequenceStarts = new ConcurrentHashMap<>(); Map<String,GenerationCounters> counters = new LinkedHashMap<>();
        AtomicBoolean writeAttempted = new AtomicBoolean(); OperationStatus status = OperationStatus.SUCCESS;
        String[] stage = {"STARTUP"};
        try {
            timed(stage, STAGES.get(0), durations, () -> {
                commandValidator.validate(command);
                reports.prepare();
                spec[0] = specReader.read();
                specValidator.validate(spec[0]);
                seed[0] = spec[0].seed() == null ? new SecureRandom().nextLong() : spec[0].seed();
                spec[0].collections().forEach(c -> counters.put(c.name(), new GenerationCounters(c.count())));
            });
            timed(stage, STAGES.get(1), durations, () -> { source.checkConnection(); source.checkReadable(); });
            timed(stage, STAGES.get(2), durations, () -> {
                if (!source.databaseExists()) throw new SourceInspectionException("Source database does not exist: " + command.source().database());
                try { sourcePlan[0] = source.inspect(); }
                catch (SourceInspectionException e) { throw e; }
                catch (RuntimeException e) { throw new SourceInspectionException("Cannot inspect source database", e); }
            });
            timed(stage, STAGES.get(3), durations, () -> preflight.validateSourceCollections(spec[0], sourcePlan[0]));
            timed(stage, STAGES.get(4), durations, () -> {
                catalog[0] = catalogs.snapshot(source, spec[0].collections(), Math.multiplyExact(spec[0].maxWorkingMegabytes(), 1024L * 1024));
                List<String> truncated = new ArrayList<>();
                spec[0].collections().forEach(c -> {
                    GenerationCounters count = counters.get(c.name());
                    count.snapshotTemplates = catalog[0].count(c.name());
                    count.snapshotBytes = catalog[0].bytes(c.name());
                    count.snapshotTruncated = catalog[0].truncated(c.name());
                    if (count.snapshotTruncated) truncated.add(c.name());
                });
                if (!truncated.isEmpty()) warnings.add("Template snapshots were truncated for collections " + truncated
                        + "; _id analysis and validate-only coverage use only the stored snapshot prefix");
            });
            timed(stage, STAGES.get(5), durations, target::checkConnection);
            timed(stage, STAGES.get(6), durations, () -> preflight.validateTargetCollections(spec[0], target.ordinaryCollections()));
            timed(stage, STAGES.get(7), durations, () -> preflight.resolveIds(spec[0], catalog[0], ids, randomStringIds,
                    sequenceStarts, randomAnalyses, warnings, preWriteWarnings));
            timed(stage, STAGES.get(8), durations, () -> {
                preflight.validateUniqueConstraints(spec[0], ids, randomStringIds, randomAnalyses,
                        command.options().allowUnprovenIds(), constraints);
                preflight.coverage(spec[0], seed[0], catalog[0], ids, randomStringIds, sequenceStarts, id);
            });
            preWriteWarnings.forEach(warning -> progress.warning(id, warning));
            if (!command.options().validateOnly()) {
                timed(stage, STAGES.get(9), durations, target::checkWritable);
                timed(stage, STAGES.get(10), durations, () -> executor.upsert(spec[0], seed[0], catalog[0], ids,
                        randomStringIds, sequenceStarts, constraints, counters, writeAttempted, id));
            } else {
                durations.put(STAGES.get(9), 0L); durations.put(STAGES.get(10), 0L);
            }
        } catch (OperationCancelledException e) {
            status = OperationStatus.CANCELLED; errors.add(issue(stage[0], e));
        } catch (RuntimeException e) {
            status = OperationStatus.FAILED; errors.add(issue(stage[0], e));
        } finally {
            if (catalog[0] != null) {
                try { catalog[0].close(); }
                catch (RuntimeException e) {
                    warnings.add("CLEANUP_SNAPSHOT: " + SecretMasker.redact(
                            Objects.toString(e.getMessage(), "template snapshot cleanup failed")));
                }
            }
            close(source); close(target);
        }
        durations.put(STAGES.get(11), 0L);
        GenerationSpec effective = spec[0];
        OperationStatus finalStatus = status;
        List<GenerationCollectionResult> results = effective == null ? List.of() : effective.collections().stream().map(item -> {
            GenerationCounters count = counters.get(item.name());
            ObjectStatus objectStatus = finalStatus == OperationStatus.SUCCESS ? ObjectStatus.COMPLETE
                    : count.written > 0 ? ObjectStatus.PARTIAL : ObjectStatus.FAILED;
            return new GenerationCollectionResult(item.name(), item.count(), count.generated, count.written,
                    count.snapshotTemplates, count.snapshotBytes, count.snapshotTruncated, count.generatedBytes,
                    ids.get(item.name()), objectStatus,
                    command.options().validateOnly() ? "validated" : objectStatus == ObjectStatus.COMPLETE ? "upserted" : "generation failed");
        }).toList();
        GenerationReport report = new GenerationReport(OperationMode.GENERATE, id, status, seed[0],
                effective == null ? null : effective.templateSelection(),
                command.options().validateOnly(), command.options().allowUnprovenIds(),
                command.source().uri(), command.target().uri(),
                command.source().database(), command.target().database(), effective == null ? "" : effective.configHash(),
                effective == null ? 0 : effective.parallelism(), effective == null ? 0 : effective.batchSize(),
                effective == null ? 0 : effective.maxWorkingMegabytes(), effective == null ? 0 : effective.maxInFlightMegabytes(),
                started, Instant.now(), durations, results, warnings, errors, !writeAttempted.get());
        reports.write(report);
        return report;
    }

    private void timed(String[] current, String stage, Map<String,Long> durations, Runnable work) {
        checkCancelled(); current[0] = stage; long start = System.nanoTime();
        try { work.run(); } finally { durations.put(stage, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)); }
    }
    private void checkCancelled() { if (cancellation.isCancellationRequested()) throw new OperationCancelledException(); }
    private static OperationIssue issue(String stage, Throwable e) { return new OperationIssue(stage, "", e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"operation failed"), FailureKind.of(e)); }
    private static void close(AutoCloseable value) { if(value!=null)try{value.close();}catch(Exception ignored){} }
}
