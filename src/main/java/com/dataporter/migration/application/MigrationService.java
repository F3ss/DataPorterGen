package com.dataporter.migration.application;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.ConfigurationValidator;
import com.dataporter.migration.domain.EndpointNormalizer;
import com.dataporter.migration.domain.ExistingTargetStrategy;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationCommand;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.migration.domain.ObjectResult;
import com.dataporter.migration.domain.PlanSelectionResult;
import com.dataporter.migration.domain.VerificationResult;
import com.dataporter.migration.domain.error.DocumentMigrationException;
import com.dataporter.migration.domain.error.MetadataMigrationException;
import com.dataporter.migration.domain.merge.MergeBatchResult;
import com.dataporter.migration.domain.merge.MergeCollectionSummary;
import com.dataporter.migration.domain.merge.MergeFingerprint;
import com.dataporter.migration.domain.merge.MergePreflightResult;
import com.dataporter.migration.domain.merge.MergeVerificationContext;
import com.dataporter.migration.ports.out.MigrationProgressReporter;
import com.dataporter.migration.ports.out.MigrationReportWriter;
import com.dataporter.migration.ports.out.MigrationSource;
import com.dataporter.migration.ports.out.MigrationTarget;
import com.dataporter.migration.ports.out.MigrationVerifier;
import com.dataporter.migration.ports.out.TransientFailureClassifier;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.domain.DatabaseObjectType;
import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.error.ConfigurationException;
import com.dataporter.shared.error.OperationCancelledException;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.error.TargetPreparationException;
import com.dataporter.shared.ports.out.BatchCursor;
import com.dataporter.shared.ports.out.CancellationToken;
import com.dataporter.shared.security.SecretMasker;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MigrationService {
    private static final List<String> STAGES = List.of("VALIDATE_CONFIGURATION", "CONNECT_SOURCE", "INSPECT_SOURCE",
            "BUILD_MIGRATION_PLAN", "CONNECT_TARGET", "VALIDATE_TARGET", "PREPARE_TARGET",
            "CREATE_COLLECTIONS", "COPY_DOCUMENTS", "CREATE_INDEXES", "CREATE_VIEWS", "VERIFY_RESULT");

    private final MigrationSource source;
    private final MigrationTarget target;
    private final MigrationVerifier verifier;
    private final MigrationReportWriter reportWriter;
    private final MigrationProgressReporter progress;
    private final CancellationToken cancellation;
    private final TransientFailureClassifier transientFailures;
    private final ConfigurationValidator configurationValidator = new ConfigurationValidator();
    private final MigrationPlanSelector planSelector = new MigrationPlanSelector();
    private final ViewOrderer viewOrderer = new ViewOrderer();

    public MigrationService(MigrationSource source, MigrationTarget target, MigrationVerifier verifier,
                            MigrationReportWriter reportWriter, MigrationProgressReporter progress, CancellationToken cancellation) {
        this(source, target, verifier, reportWriter, progress, cancellation, failure -> false);
    }

    public MigrationService(MigrationSource source, MigrationTarget target, MigrationVerifier verifier,
                            MigrationReportWriter reportWriter, MigrationProgressReporter progress, CancellationToken cancellation,
                            TransientFailureClassifier transientFailures) {
        this.source = source;
        this.target = target;
        this.verifier = verifier;
        this.reportWriter = reportWriter;
        this.progress = progress;
        this.cancellation = cancellation;
        this.transientFailures = transientFailures;
    }

    public MigrationReport migrate(MigrationCommand command) {
        String id = UUID.randomUUID().toString();
        Instant started = Instant.now();
        Map<String, Long> durations = new LinkedHashMap<>();
        List<ObjectResult> objects = Collections.synchronizedList(new ArrayList<>());
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        List<OperationIssue> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean targetMutated = new AtomicBoolean();
        MigrationPlan[] plan = {null};
        MergePreflightResult[] mergePreflight = {null};
        Map<String, MergeCollectionSummary> mergeSummaries = new ConcurrentHashMap<>();
        VerificationResult[] verification = {VerificationResult.skipped()};
        OperationStatus status = OperationStatus.SUCCESS;
        warnings.add("BASIC consistency does not provide a database-wide snapshot; concurrent source changes can produce a logically inconsistent result");

        try {
            timed(id, STAGES.get(0), durations, () -> {
                configurationValidator.validate(command);
                reportWriter.prepare();
            });
            var retry = new ExponentialBackoffRetryPolicy(command.options().retry(), transientFailures::isTransient);
            timed(id, STAGES.get(1), durations, () -> {
                retry.execute("source connection", () -> { source.checkConnection(); source.checkReadable(); return null; });
            });
            timed(id, STAGES.get(2), durations, () -> {
                if (!retry.execute("source database existence", source::databaseExists))
                    throw new SourceInspectionException("Source database does not exist: " + command.source().database());
                plan[0] = retry.execute("source inspection", source::inspect);
            });
            timed(id, STAGES.get(3), durations, () -> {
                PlanSelectionResult selected = planSelector.select(plan[0], command.options().collectionSelection());
                plan[0] = new MigrationPlan(selected.plan().collections(), selected.plan().indexes(),
                        viewOrderer.order(selected.plan().views()));
                objects.addAll(selected.skipped());
            });
            timed(id, STAGES.get(4), durations, () -> {
                retry.execute("target connection", () -> {
                    target.checkConnection();
                    return null;
                });
                verifyDistinctCluster(command);
            });
            timed(id, STAGES.get(5), durations, () -> mergePreflight[0] = validateTarget(command, plan[0], objects));
            timed(id, STAGES.get(6), durations, () -> prepareTarget(command, targetMutated));
            timed(id, STAGES.get(7), durations, () -> plan[0].collections().forEach(definition -> {
                checkCancelled();
                if (mergePreflight[0] == null || mergePreflight[0].createsCollection(definition.name())) {
                    targetMutated.set(true);
                    target.createCollection(definition);
                }
            }));
            timed(id, STAGES.get(8), durations, () -> copyDocuments(id, command, plan[0], objects, errors,
                    targetMutated, mergePreflight[0], mergeSummaries));
            timed(id, STAGES.get(9), durations, () -> {
                int total = plan[0].indexes().size();
                for (int position = 0; position < total; position++) {
                    IndexDefinition index = plan[0].indexes().get(position);
                    checkCancelled();
                    progress.indexStarted(id, index.collection(), index.name(), position + 1, total);
                    if (mergePreflight[0] != null && !mergePreflight[0].createsIndex(index)) {
                        objects.add(new ObjectResult(index.name(), DatabaseObjectType.INDEX, ObjectStatus.SKIPPED,
                                0, 0, "retained equivalent target index on " + index.collection()));
                        continue;
                    }
                    try {
                        targetMutated.set(true);
                        target.createIndex(index);
                        objects.add(new ObjectResult(index.name(), DatabaseObjectType.INDEX, ObjectStatus.COMPLETE,
                                0, 0, "created on " + index.collection()));
                    }
                    catch (RuntimeException e) {
                        objects.add(new ObjectResult(index.name(), DatabaseObjectType.INDEX, ObjectStatus.FAILED,
                                0, 0, SecretMasker.redact(e.getMessage())));
                        errors.add(issue("CREATE_INDEXES", index.collection() + "." + index.name(), e));
                        throw new MetadataMigrationException("Failed to create index " + index.name(), e);
                    }
                }
            });
            timed(id, STAGES.get(10), durations, () -> plan[0].views().forEach(view -> {
                checkCancelled();
                if (mergePreflight[0] != null && !mergePreflight[0].createsView(view.name())) {
                    objects.add(new ObjectResult(view.name(), DatabaseObjectType.VIEW, ObjectStatus.SKIPPED,
                            0, 0, "retained equivalent target view from " + view.viewOn()));
                    return;
                }
                try {
                    targetMutated.set(true);
                    target.createView(view);
                    objects.add(new ObjectResult(view.name(), DatabaseObjectType.VIEW, ObjectStatus.COMPLETE,
                            0, 0, "created from " + view.viewOn()));
                }
                catch (RuntimeException e) {
                    errors.add(issue("CREATE_VIEWS", view.name(), e));
                    throw new MetadataMigrationException("Failed to create view " + view.name(), e);
                }
            }));
            timed(id, STAGES.get(11), durations, () -> {
                if (command.options().verificationEnabled()) {
                    if (mergePreflight[0] == null) verification[0] = verifier.verify(plan[0], command.options().verificationLevel());
                    else verification[0] = verifier.verifyMerge(plan[0], command.options().verificationLevel(),
                            new MergeVerificationContext(mergeSummaries));
                }
            });
            if (!errors.isEmpty()) status = OperationStatus.FAILED;
            else if (!verification[0].successful()) status = OperationStatus.VERIFICATION_FAILED;
        } catch (OperationCancelledException e) {
            status = OperationStatus.CANCELLED;
            errors.add(issue("CANCELLED", "", e));
        } catch (RuntimeException e) {
            status = OperationStatus.FAILED;
            if (errors.isEmpty()) errors.add(issue(currentStage(durations), "", e));
        } finally {
            safeClose(source);
            safeClose(target);
        }

        boolean safeToRetry = !targetMutated.get();
        MigrationReport report = new MigrationReport(id, status, command.source().uri(), command.target().uri(),
                command.source().database(), command.target().database(), command.options().existingTargetStrategy(),
                command.options().consistencyMode(), command.options().verificationLevel(),
                command.options().collectionSelection(), started, Instant.now(),
                durations, objects, warnings, errors, verification[0], safeToRetry);
        reportWriter.write(report);
        progress.completed(report);
        return report;
    }

    private void verifyDistinctCluster(MigrationCommand command) {
        if (!command.source().database().equals(command.target().database())) return;
        if (!EndpointNormalizer.clusterHosts(command.source().uri()).isEmpty()
                && EndpointNormalizer.clusterHosts(command.source().uri()).equals(EndpointNormalizer.clusterHosts(command.target().uri())))
            throw new ConfigurationException("source and target resolve to the same cluster and database");
        var sourceHosts = source.clusterHosts();
        var targetHosts = target.clusterHosts();
        if (sourceHosts.isPresent() && targetHosts.isPresent()
                && !java.util.Collections.disjoint(sourceHosts.get(), targetHosts.get()))
            throw new ConfigurationException("source and target resolve to the same cluster and database");
    }

    private MergePreflightResult validateTarget(MigrationCommand command, MigrationPlan plan,
                                                List<ObjectResult> objects) {
        if (command.options().existingTargetStrategy() == ExistingTargetStrategy.FAIL_IF_EXISTS && target.hasUserObjects())
            throw new TargetPreparationException("Target database already contains user collections or views");
        if (command.options().existingTargetStrategy() != ExistingTargetStrategy.MERGE) return null;

        return target.preflightMerge(plan);
    }

    private void prepareTarget(MigrationCommand command, AtomicBoolean targetMutated) {
        target.checkWritable();
        if (command.options().existingTargetStrategy() == ExistingTargetStrategy.DROP_AND_RECREATE && target.databaseExists()) {
            targetMutated.set(true);
            target.dropDatabase();
        }
    }

    private void copyDocuments(String id, MigrationCommand command, MigrationPlan plan, List<ObjectResult> results,
                               List<OperationIssue> errors, AtomicBoolean targetMutated,
                               MergePreflightResult mergePreflight,
                               Map<String, MergeCollectionSummary> mergeSummaries) {
        int parallelism = command.options().parallelism();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, parallelism * 2)), new ThreadPoolExecutor.CallerRunsPolicy());
        Map<String, ObjectResult> completed = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (CollectionDefinition collection : plan.collections()) {
                futures.add(executor.submit(() -> copyCollection(id, command, collection, completed, errors,
                        targetMutated, mergePreflight, mergeSummaries)));
            }
            for (Future<?> future : futures) {
                try { future.get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OperationCancelledException(); }
                catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new DocumentMigrationException("Collection copy failed", e);
                }
            }
        } finally {
            executor.shutdownNow();
            try { executor.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (CollectionDefinition collection : plan.collections()) {
                ObjectResult result = completed.get(collection.name());
                if (result != null) results.add(result);
            }
        }
    }

    private void copyCollection(String id, MigrationCommand command, CollectionDefinition collection,
                                Map<String, ObjectResult> results, List<OperationIssue> errors, AtomicBoolean targetMutated,
                                MergePreflightResult mergePreflight,
                                Map<String, MergeCollectionSummary> mergeSummaries) {
        if (mergePreflight != null) {
            mergeCollection(id, command, collection, results, errors, targetMutated, mergePreflight, mergeSummaries);
            return;
        }
        long documents = 0, bytes = 0;
        try (BatchCursor cursor = source.openBatches(collection.name(), command.options().batchSize())) {
            for (DataBatch batch; (batch = cursor.next()) != null; ) {
                checkCancelled();
                if (!batch.isEmpty()) targetMutated.set(true);
                target.writeBatch(batch);
                documents += batch.documents().size();
                bytes += batch.bytes();
                progress.collectionProgress(id, collection.name(), documents, bytes);
            }
            results.put(collection.name(), new ObjectResult(collection.name(), DatabaseObjectType.COLLECTION, ObjectStatus.COMPLETE,
                    documents, bytes, "copied"));
        } catch (OperationCancelledException e) {
            results.put(collection.name(), new ObjectResult(collection.name(), DatabaseObjectType.COLLECTION, ObjectStatus.PARTIAL,
                    documents, bytes, "cancelled"));
            throw e;
        } catch (RuntimeException e) {
            results.put(collection.name(), new ObjectResult(collection.name(), DatabaseObjectType.COLLECTION,
                    documents == 0 ? ObjectStatus.FAILED : ObjectStatus.PARTIAL, documents, bytes, SecretMasker.redact(e.getMessage())));
            errors.add(issue("COPY_DOCUMENTS", collection.name(), e));
            if (!command.options().continueOnCollectionError())
                throw new DocumentMigrationException("Failed to copy collection " + collection.name(), e);
        }
    }

    private void mergeCollection(String id, MigrationCommand command, CollectionDefinition collection,
                                 Map<String, ObjectResult> results, List<OperationIssue> errors,
                                 AtomicBoolean targetMutated, MergePreflightResult preflight,
                                 Map<String, MergeCollectionSummary> summaries) {
        long sourceDocuments = 0, bytes = 0, inserted = 0, replaced = 0, conflicts = 0;
        MergeFingerprint.Accumulator fingerprint = MergeFingerprint.accumulator();
        try (BatchCursor cursor = source.openBatches(collection.name(), command.options().batchSize())) {
            for (DataBatch batch; (batch = cursor.next()) != null; ) {
                checkCancelled();
                if (!batch.isEmpty()) targetMutated.set(true);
                MergeBatchResult result = target.mergeBatch(batch);
                if (result.processed() != batch.documents().size())
                    throw new DocumentMigrationException("MERGE returned inconsistent counters for " + collection.name(),
                            new IllegalStateException("invalid MERGE batch result"));
                sourceDocuments += batch.documents().size();
                bytes += batch.bytes();
                inserted += result.inserted();
                replaced += result.replaced();
                conflicts += result.replaced();
                if (!result.expectedTargetFingerprint().isBlank())
                    fingerprint.addBatch(result.expectedTargetFingerprint());
                progress.collectionProgress(id, collection.name(), sourceDocuments, bytes);
            }
            MergeCollectionSummary summary = new MergeCollectionSummary(
                    preflight.initialDocumentCounts().getOrDefault(collection.name(), 0L), sourceDocuments,
                    inserted, replaced, conflicts, bytes, fingerprint.finish());
            summaries.put(collection.name(), summary);
            results.put(collection.name(), ObjectResult.mergeCollection(collection.name(), ObjectStatus.COMPLETE, sourceDocuments,
                    bytes, inserted, replaced, conflicts, "merged"));
        } catch (OperationCancelledException e) {
            results.put(collection.name(), ObjectResult.mergeCollection(collection.name(), ObjectStatus.PARTIAL, sourceDocuments,
                    bytes, inserted, replaced, conflicts, "cancelled"));
            throw e;
        } catch (RuntimeException e) {
            long safeConflicts = Math.max(conflicts, 1);
            summaries.put(collection.name(), new MergeCollectionSummary(
                    preflight.initialDocumentCounts().getOrDefault(collection.name(), 0L), sourceDocuments,
                    inserted, replaced, safeConflicts, bytes));
            results.put(collection.name(), ObjectResult.mergeCollection(collection.name(),
                    sourceDocuments == 0 ? ObjectStatus.FAILED : ObjectStatus.PARTIAL,
                    sourceDocuments, bytes, inserted, replaced, safeConflicts,
                    SecretMasker.redact(e.getMessage())));
            errors.add(issue("COPY_DOCUMENTS", collection.name(), e));
            if (!command.options().continueOnCollectionError())
                throw new DocumentMigrationException("Failed to merge collection " + collection.name(), e);
        }
    }

    private void timed(String id, String stage, Map<String, Long> durations, Runnable work) {
        checkCancelled();
        progress.stageStarted(id, stage);
        long start = System.nanoTime();
        try { work.run(); }
        finally { durations.put(stage, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); }
    }

    private void checkCancelled() { if (cancellation.isCancellationRequested()) throw new OperationCancelledException(); }
    private OperationIssue issue(String stage, String object, Throwable e) {
        return new OperationIssue(stage, object, e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "operation failed"),
                FailureKind.of(e));
    }
    private String currentStage(Map<String, Long> durations) {
        return durations.isEmpty() ? "STARTUP" : new ArrayList<>(durations.keySet()).getLast();
    }
    private void safeClose(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { /* report is already authoritative */ }
    }
}
