package com.dataporter.migration.application;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.CollectionSelection;
import com.dataporter.migration.domain.ConsistencyMode;
import com.dataporter.migration.domain.ExistingTargetStrategy;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationCommand;
import com.dataporter.migration.domain.MigrationOptions;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.migration.domain.ObjectResult;
import com.dataporter.migration.domain.RetrySettings;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.migration.domain.VerificationResult;
import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.migration.domain.error.MetadataMigrationException;
import com.dataporter.migration.domain.merge.MergeBatchResult;
import com.dataporter.migration.domain.merge.MergePreflightResult;
import com.dataporter.migration.ports.out.MigrationProgressReporter;
import com.dataporter.migration.ports.out.MigrationReportWriter;
import com.dataporter.migration.ports.out.MigrationSource;
import com.dataporter.migration.ports.out.MigrationTarget;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.domain.DatabaseObjectType;
import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.error.ConfigurationException;
import com.dataporter.shared.ports.out.BatchCursor;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class MigrationServiceTest {
    @Test
    void executesPipelineInStableOrder() {
        var events = new ArrayList<String>();
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> new VerificationResult(true, List.of()),
                report -> {}, new RecordingProgress(events), () -> false);

        MigrationReport report = service.migrate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(events).containsExactly("VALIDATE_CONFIGURATION", "CONNECT_SOURCE", "INSPECT_SOURCE",
                "BUILD_MIGRATION_PLAN", "CONNECT_TARGET", "VALIDATE_TARGET",
                "PREPARE_TARGET", "CREATE_COLLECTIONS", "COPY_DOCUMENTS", "CREATE_INDEXES", "CREATE_VIEWS", "VERIFY_RESULT");
        assertThat(target.written).isEqualTo(1);
    }

    @Test
    void continuesIndependentCollectionsButReportsFailure() {
        var source = new FakeSource(plan("bad", "good"));
        var target = new FakeTarget();
        target.failCollection = "bad";
        var service = new MigrationService(source, target, (plan, level) -> new VerificationResult(true, List.of()),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(true));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.objects()).filteredOn(o -> o.name().equals("good"))
                .extracting(ObjectResult::status).containsExactly(ObjectStatus.COMPLETE);
        assertThat(report.errors()).hasSize(1);
    }

    @Test
    void cancellationProducesStableCancelledStatus() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> true);

        assertThat(service.migrate(command(false)).status()).isEqualTo(OperationStatus.CANCELLED);
    }

    @Test
    void verificationDifferencesHaveDedicatedStatus() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target,
                (plan, level) -> new VerificationResult(false, List.of("count mismatch")),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        assertThat(service.migrate(command(false)).status()).isEqualTo(OperationStatus.VERIFICATION_FAILED);
    }

    @Test
    void failIfExistsStopsBeforeCreatingOrCopying() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        target.hasObjects = true;
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(target.written).isZero();
    }

    @Test
    void failIfExistsRejectsNonEmptyTargetBeforeWriteProbe() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        target.hasObjects = true;
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(target.writeChecks).isZero();
    }

    @Test
    void unwritableReportSinkFailsConfigurationBeforeAnyConnection() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                failingReportWriter(), MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.stage()).isEqualTo("VALIDATE_CONFIGURATION");
            assertThat(issue.failureKind()).isEqualTo(FailureKind.CONFIGURATION);
        });
        assertThat(target.connectionChecks).isZero();
    }

    @Test
    void rejectsSameDatabaseWhenResolvedTopologiesIntersect() {
        var source = new FakeSource(plan("customers"));
        source.resolvedHosts = java.util.Set.of("127.0.0.1:27017");
        var target = new FakeTarget();
        target.resolvedHosts = java.util.Set.of("127.0.0.1:27017", "0:0:0:0:0:0:0:1:27017");
        var command = new MigrationCommand(new Endpoint("mongodb://localhost:27017", "a"),
                new Endpoint("mongodb://127.0.0.1:27017", "a"),
                new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, 10, 1,
                        true, VerificationLevel.METADATA_AND_COUNTS, CollectionSelection.all(), false,
                        new RetrySettings(1, 0, 0)));
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command);

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.stage()).isEqualTo("CONNECT_TARGET");
            assertThat(issue.failureKind()).isEqualTo(FailureKind.CONFIGURATION);
            assertThat(issue.message()).contains("same cluster");
        });
        assertThat(target.writeChecks).isZero();
        assertThat(target.createdCollections).isZero();
    }

    @Test
    void allowsSameDatabaseNameWhenResolvedTopologiesAreDisjoint() {
        var source = new FakeSource(plan("customers"));
        source.resolvedHosts = java.util.Set.of("10.0.0.1:27017");
        var target = new FakeTarget();
        target.resolvedHosts = java.util.Set.of("10.0.0.2:27017");
        var command = new MigrationCommand(new Endpoint("mongodb://one:27017", "a"),
                new Endpoint("mongodb://two:27017", "a"),
                new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, 10, 1,
                        true, VerificationLevel.METADATA_AND_COUNTS, CollectionSelection.all(), false,
                        new RetrySettings(1, 0, 0)));
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command);

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.written).isEqualTo(1);
    }

    private static MigrationReportWriter failingReportWriter() {
        return new MigrationReportWriter() {
            @Override public void prepare() { throw new ConfigurationException("Cannot write migration report"); }
            @Override public void write(MigrationReport report) {}
        };
    }

    @Test
    void dropStrategyDropsExistingDatabaseBeforeCopy() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        target.databaseExists = true;
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.DROP_AND_RECREATE));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.dropped).isTrue();
        assertThat(target.writeChecks).isEqualTo(1);
    }

    @Test
    void dropStrategyProbesWritableTargetBeforeDropping() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        target.databaseExists = true;
        target.writeProbeFailure = new IllegalStateException("not writable");
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.DROP_AND_RECREATE));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(target.dropped).isFalse();
    }

    @Test
    void unknownIncludedCollectionFailsBeforeTargetIsContacted() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.FAIL_IF_EXISTS,
                CollectionSelection.from(List.of("missing"), List.of())));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors()).extracting(OperationIssue::message)
                .anySatisfy(message -> assertThat(message).contains("ConfigurationException", "missing"));
        assertThat(target.connectionChecks).isZero();
        assertThat(target.writeChecks).isZero();
    }

    @Test
    void filteredObjectsAreReportedAsSkippedAndNeverCopied() {
        var source = new FakeSource(plan("customers", "events"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.FAIL_IF_EXISTS,
                CollectionSelection.from(List.of("customers"), List.of())));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.objects()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("events");
            assertThat(item.status()).isEqualTo(ObjectStatus.SKIPPED);
        });
        assertThat(target.written).isEqualTo(1);
    }

    @Test
    void mergeBatchReportsBulkInsertsAndOverwrites() {
        var source = new FakeSource(plan("customers"), 2);
        var target = new FakeTarget();
        target.preflight = new MergePreflightResult(List.of(), List.of(), List.of(), Map.of("customers", 1L));
        target.mergeResult = new MergeBatchResult(1, 1);
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.MERGE));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.safeToRetry()).isFalse();
        assertThat(report.objects()).filteredOn(item -> item.name().equals("customers")).singleElement()
                .satisfies(item -> {
                    assertThat(item.sourceDocuments()).isEqualTo(2);
                    assertThat(item.insertedDocuments()).isEqualTo(1);
                    assertThat(item.replacedDocuments()).isEqualTo(1);
                    assertThat(item.conflicts()).isEqualTo(1);
                });
    }

    @Test
    void mergePreflightNoLongerScansSourceIds() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.MERGE));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(source.openedCursors).isEqualTo(1);
        assertThat(target.mergeCalls).isEqualTo(1);
    }

    @Test
    void mergeFailureMarksCollectionFailedAndRunUnsafeToRetry() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        target.mergeFailure = new IllegalStateException("merge write failed");
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.MERGE));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.safeToRetry()).isFalse();
        assertThat(report.objects()).filteredOn(item -> item.name().equals("customers")).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(ObjectStatus.FAILED));
        assertThat(report.errors()).singleElement().satisfies(issue ->
                assertThat(issue.stage()).isEqualTo("COPY_DOCUMENTS"));
    }

    @Test
    void mergeRejectsInconsistentBatchCounters() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        target.mergeResult = new MergeBatchResult(0, 0);
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.MERGE));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.objects()).filteredOn(item -> item.name().equals("customers")).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(ObjectStatus.FAILED);
                    assertThat(item.detail()).contains("inconsistent counters");
                });
    }

    @Test
    void mergeAppliesCollectionSelectionBeforeTargetPreflight() {
        var source = new FakeSource(plan("customers", "events"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.MERGE,
                CollectionSelection.from(List.of("customers"), List.of())));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.preflightSourcePlan.collections()).extracting(CollectionDefinition::name)
                .containsExactly("customers");
        assertThat(report.objects()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("events");
            assertThat(item.status()).isEqualTo(ObjectStatus.SKIPPED);
        });
    }

    @Test
    void mergeMetadataConflictStopsBeforeCollectionsAndDocuments() {
        var source = new FakeSource(plan("customers"));
        var target = new FakeTarget();
        target.preflightFailure = new MetadataMigrationException("Incompatible collection options for customers",
                new IllegalStateException());
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.MERGE));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(target.createdCollections).isZero();
        assertThat(target.mergeCalls).isZero();
        assertThat(report.safeToRetry()).isTrue();
    }

    @Test
    void mergeRetainsEquivalentIndexesAndViewsInSourceOrder() {
        var indexes = List.of(new IndexDefinition("customers", "a_idx", BsonPayload.emptyArray()),
                new IndexDefinition("customers", "b_idx", BsonPayload.emptyArray()));
        var view = new ViewDefinition("active_customers", "customers", BsonPayload.emptyArray());
        var source = new FakeSource(new MigrationPlan(
                List.of(new CollectionDefinition("customers", BsonPayload.emptyArray())), indexes, List.of(view)));
        var target = new FakeTarget();
        target.preflight = new MergePreflightResult(List.of(), List.of(indexes.get(1)), List.of(), Map.of("customers", 0L));
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false, ExistingTargetStrategy.MERGE));

        assertThat(target.createdIndexes).containsExactly("b_idx");
        assertThat(target.createdViews).isEmpty();
        assertThat(report.objects()).filteredOn(item -> item.type() == DatabaseObjectType.INDEX)
                .extracting(ObjectResult::name, ObjectResult::status)
                .containsExactly(tuple("a_idx", ObjectStatus.SKIPPED), tuple("b_idx", ObjectStatus.COMPLETE));
        assertThat(report.objects()).filteredOn(item -> item.type() == DatabaseObjectType.VIEW)
                .singleElement().satisfies(item -> assertThat(item.status()).isEqualTo(ObjectStatus.SKIPPED));
    }

    @Test
    void reportsEverySecondaryIndexAsItStartsAndCompletes() {
        var indexes = List.of(
                new IndexDefinition("customers", "email_unique", BsonPayload.emptyArray()),
                new IndexDefinition("customers", "name_sparse", BsonPayload.emptyArray()),
                new IndexDefinition("orders", "customer_id", BsonPayload.emptyArray()));
        var source = new FakeSource(new MigrationPlan(
                List.of(new CollectionDefinition("customers", BsonPayload.emptyArray()),
                        new CollectionDefinition("orders", BsonPayload.emptyArray())), indexes, List.of()));
        var target = new FakeTarget();
        var progress = new IndexRecordingProgress();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, progress, () -> false);

        MigrationReport report = service.migrate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(progress.indexes).containsExactly(
                "1/3 customers.email_unique", "2/3 customers.name_sparse", "3/3 orders.customer_id");
        assertThat(report.objects()).filteredOn(item -> item.type() == DatabaseObjectType.INDEX)
                .extracting(ObjectResult::name).containsExactly("email_unique", "name_sparse", "customer_id");
    }

    @Test
    void indexFailureReportKeepsSafeMongoErrorClassification() {
        var index = new IndexDefinition("customers", "email_unique", BsonPayload.emptyArray());
        var source = new FakeSource(new MigrationPlan(
                List.of(new CollectionDefinition("customers", BsonPayload.emptyArray())), List.of(index), List.of()));
        var target = new FakeTarget();
        target.failIndex = "email_unique";
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.stage()).isEqualTo("CREATE_INDEXES");
            assertThat(issue.object()).isEqualTo("customers.email_unique");
            assertThat(issue.message()).contains("MongoDB code=85", "codeName=IndexOptionsConflict");
        });
        assertThat(report.objects()).filteredOn(item -> item.type() == DatabaseObjectType.INDEX)
                .singleElement().satisfies(item -> {
                    assertThat(item.name()).isEqualTo("email_unique");
                    assertThat(item.status()).isEqualTo(ObjectStatus.FAILED);
                    assertThat(item.detail()).contains("codeName=IndexOptionsConflict");
                });
    }

    @Test
    void parallelCopyResultsAreReportedInPlanOrderRegardlessOfCompletionOrder() {
        var source = new GatedSource(plan("slow", "fast"));
        var target = new FakeTarget();
        var service = new MigrationService(source, target, (plan, level) -> VerificationResult.skipped(),
                report -> {}, MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(new MigrationCommand(
                new Endpoint("mongodb://source:27017", "a"), new Endpoint("mongodb://target:27017", "b"),
                new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, 10, 2,
                        true, VerificationLevel.METADATA_AND_COUNTS, CollectionSelection.all(), false,
                        new RetrySettings(1, 0, 0))));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.written).isEqualTo(2);
        assertThat(report.objects()).extracting(ObjectResult::name).containsExactly("slow", "fast");
    }

    private static final class GatedSource extends FakeSource {
        private final java.util.concurrent.CountDownLatch fastFinished = new java.util.concurrent.CountDownLatch(1);
        private final String gatedCollection;
        GatedSource(MigrationPlan plan) {
            super(plan);
            this.gatedCollection = plan.collections().getFirst().name();
        }
        @Override public BatchCursor openBatches(String collection, int size) {
            BatchCursor delegate = super.openBatches(collection, size);
            if (collection.equals(gatedCollection))
                return new BatchCursor() {
                    public DataBatch next() {
                        try { fastFinished.await(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        return delegate.next();
                    }
                    public void close() { delegate.close(); }
                };
            return new BatchCursor() {
                public DataBatch next() { return delegate.next(); }
                public void close() { fastFinished.countDown(); delegate.close(); }
            };
        }
    }

    private static MigrationPlan plan(String... names) {
        return new MigrationPlan(Arrays.stream(names).map(n -> new CollectionDefinition(n, BsonPayload.emptyArray())).toList(),
                List.of(), List.of());
    }

    private static MigrationCommand command(boolean continueOnError) {
        return command(continueOnError, ExistingTargetStrategy.FAIL_IF_EXISTS);
    }

    private static MigrationCommand command(boolean continueOnError, ExistingTargetStrategy strategy) {
        return command(continueOnError, strategy, CollectionSelection.all());
    }

    private static MigrationCommand command(boolean continueOnError, ExistingTargetStrategy strategy,
                                            CollectionSelection selection) {
        return new MigrationCommand(new Endpoint("mongodb://source:27017", "a"), new Endpoint("mongodb://target:27017", "b"),
                new MigrationOptions(strategy, ConsistencyMode.BASIC, 10, 1,
                        true, VerificationLevel.METADATA_AND_COUNTS, selection, continueOnError,
                        new RetrySettings(1, 0, 0)));
    }

    private static class FakeSource implements MigrationSource {
        private final MigrationPlan plan;
        private final int documents;
        int openedCursors;
        java.util.Set<String> resolvedHosts;
        FakeSource(MigrationPlan plan) { this(plan, 1); }
        FakeSource(MigrationPlan plan, int documents) { this.plan = plan; this.documents = documents; }
        @Override public java.util.Optional<java.util.Set<String>> clusterHosts() { return java.util.Optional.ofNullable(resolvedHosts); }
        public void checkConnection() {}
        public void checkReadable() {}
        public boolean databaseExists() { return true; }
        public MigrationPlan inspect() { return plan; }
        public BatchCursor openBatches(String collection, int size) {
            openedCursors++;
            return new BatchCursor() {
                boolean consumed;
                public DataBatch next() {
                    if (consumed) return null;
                    consumed = true;
                    var payload = BsonPayload.emptyArray();
                    return new DataBatch(collection, Collections.nCopies(documents, payload), (long) payload.size() * documents);
                }
                public void close() {}
            };
        }
        public long count(String collection) { return documents; }
        public void close() {}
    }

    private static final class FakeTarget implements MigrationTarget {
        int written;
        String failCollection;
        boolean hasObjects;
        boolean databaseExists;
        boolean dropped;
        String failIndex;
        int connectionChecks;
        int writeChecks;
        RuntimeException writeProbeFailure;
        int createdCollections;
        int mergeCalls;
        RuntimeException preflightFailure;
        RuntimeException mergeFailure;
        MergePreflightResult preflight;
        MigrationPlan preflightSourcePlan;
        MergeBatchResult mergeResult;
        final List<String> createdIndexes = new ArrayList<>();
        final List<String> createdViews = new ArrayList<>();
        java.util.Set<String> resolvedHosts;
        @Override public java.util.Optional<java.util.Set<String>> clusterHosts() { return java.util.Optional.ofNullable(resolvedHosts); }
        public void checkConnection() { connectionChecks++; }
        public void checkWritable() {
            writeChecks++;
            if (writeProbeFailure != null) throw writeProbeFailure;
        }
        public boolean databaseExists() { return databaseExists; }
        public boolean hasUserObjects() { return hasObjects; }
        public MigrationPlan inspect() { return new MigrationPlan(List.of(), List.of(), List.of()); }
        public BatchCursor openBatches(String collection, int size) { throw new UnsupportedOperationException(); }
        public long count(String collection) { return written; }
        public void dropDatabase() { dropped = true; }
        public MergePreflightResult preflightMerge(MigrationPlan sourcePlan) {
            preflightSourcePlan = sourcePlan;
            if (preflightFailure != null) throw preflightFailure;
            if (preflight != null) return preflight;
            return new MergePreflightResult(sourcePlan.collections().stream().map(CollectionDefinition::name).toList(),
                    sourcePlan.indexes(), sourcePlan.views().stream().map(ViewDefinition::name).toList(),
                    sourcePlan.collections().stream().collect(java.util.stream.Collectors.toMap(
                            CollectionDefinition::name, ignored -> 0L)));
        }
        public MergeBatchResult mergeBatch(DataBatch batch) {
            mergeCalls++;
            if (mergeFailure != null) throw mergeFailure;
            if (mergeResult != null) return mergeResult;
            return new MergeBatchResult(batch.documents().size(), 0);
        }
        public void createCollection(CollectionDefinition definition) { createdCollections++; }
        public void writeBatch(DataBatch batch) {
            if (batch.collection().equals(failCollection)) throw new IllegalStateException("write failed");
            written += batch.documents().size();
        }
        public void createIndex(IndexDefinition definition) {
            if (definition.name().equals(failIndex)) {
                throw new MetadataMigrationException("Failed to create index " + definition.name()
                        + " on " + definition.collection()
                        + " [MongoDB code=85, codeName=IndexOptionsConflict]", new IllegalStateException());
            }
            createdIndexes.add(definition.name());
        }
        public void createView(ViewDefinition definition) { createdViews.add(definition.name()); }
        public void close() {}
    }

    private record RecordingProgress(List<String> events) implements MigrationProgressReporter {
        public void stageStarted(String migrationId, String stage) { events.add(stage); }
        public void collectionProgress(String migrationId, String collection, long documents, long bytes) {}
        public void completed(MigrationReport report) {}
    }

    private static final class IndexRecordingProgress implements MigrationProgressReporter {
        private final List<String> indexes = new ArrayList<>();
        public void stageStarted(String migrationId, String stage) {}
        public void collectionProgress(String migrationId, String collection, long documents, long bytes) {}
        public void indexStarted(String migrationId, String collection, String index, int ordinal, int total) {
            indexes.add(ordinal + "/" + total + " " + collection + "." + index);
        }
        public void completed(MigrationReport report) {}
    }
}
