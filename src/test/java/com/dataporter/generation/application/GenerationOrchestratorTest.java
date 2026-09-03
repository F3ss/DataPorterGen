package com.dataporter.generation.application;

import com.dataporter.adapters.snapshot.FileTemplateCatalogFactory;
import com.dataporter.adapters.mongo.MongoGenerationBsonEngine;
import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationCommand;
import com.dataporter.generation.domain.GenerationOptions;
import com.dataporter.generation.domain.GenerationReport;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.TemplateSelection;
import com.dataporter.generation.domain.TemplateQuery;
import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.generation.ports.out.GenerationSource;
import com.dataporter.generation.ports.out.GenerationTarget;
import com.dataporter.generation.ports.out.TemplateCatalog;
import com.dataporter.generation.ports.out.TemplateCatalogFactory;
import com.dataporter.generation.domain.GenerationSourceInspection;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.error.ConfigurationException;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.ports.out.BatchCursor;

import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class GenerationOrchestratorTest {
    @Test void emptyFilteredSnapshotFailsBeforeTargetConnection() {
        FakeTarget target = new FakeTarget();
        TemplateQuery query = new TemplateQuery(Map.of("required", Map.of("$exists", true)));
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 1, 1, 10, 2,
                List.of(new CollectionGenerationSpec("items", 1, query,
                        Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED)))), "hash");
        GenerationSource source = new GenerationSource() {
            public void checkConnection() { }
            public void checkReadable() { }
            public boolean databaseExists() { return true; }
            public GenerationSourceInspection inspect() {
                return new GenerationSourceInspection(List.of("items"), List.of());
            }
            public BatchCursor openBatches(String collection, int batchSize) {
                throw new AssertionError("unfiltered cursor must not be used");
            }
            public BatchCursor openTemplateBatches(String collection, TemplateQuery actual, int batchSize) {
                assertThat(actual).isSameAs(query);
                return new BatchCursor() {
                    public DataBatch next() { return null; }
                    public void close() { }
                };
            }
            public void close() { }
        };
        GenerationOrchestrator service = new GenerationOrchestrator(source, target, () -> spec,
                new FileTemplateCatalogFactory(), new MongoGenerationBsonEngine(), ignored -> {}, () -> false);

        GenerationReport report = service.generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.safeToRetry()).isTrue();
        assertThat(report.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.stage()).isEqualTo("SNAPSHOT_TEMPLATES");
            assertThat(issue.message()).contains("query matched no template documents")
                    .doesNotContain("required", "$exists");
        });
        assertThat(target.connections).isZero();
        assertThat(target.collectionInspections).isZero();
        assertThat(target.writeChecks).isZero();
        assertThat(target.written).isZero();
    }

    @Test void sharedDatesFlowThroughCoverageAndParallelWrites() {
        FakeTarget target = new FakeTarget();
        Map<String, GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED));
        fields.put("/operationDate", new DateTime(new SharedDateRef("operationDate"), DateOutput.BSON_DATE,
                null, "UTC", "ROOT", RuleOptions.REQUIRED));
        fields.put("/legacyDate", new DateTime(new SharedDateRef("operationDate"), DateOutput.STRING,
                "'1'yyMMdd", "UTC", "ROOT", RuleOptions.REQUIRED));
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 1, 2, 10, 2,
                Map.of("operationDate", new SharedDateDefinition(
                        new FixedDate(java.time.Instant.parse("2026-09-02T10:20:30.123456Z")))),
                List.of(new CollectionGenerationSpec("items", 2, fields)), "hash");

        GenerationReport report = service(target, spec, false).generate(command(false));

        assertThat(report.status()).as(report.errors().toString()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.payloads).hasSize(2).allSatisfy(payload -> {
            BsonDocument document = new RawBsonDocument(payload.bytes());
            assertThat(document.getDateTime("operationDate").getValue()).isEqualTo(1_788_344_430_123L);
            assertThat(document.getString("legacyDate").getValue()).isEqualTo("1260902");
        });
    }

    @Test void unwritableReportSinkFailsConfigurationBeforeAnyConnection() {
        FakeTarget target = new FakeTarget();
        com.dataporter.generation.ports.out.GenerationReportWriter writer =
                new com.dataporter.generation.ports.out.GenerationReportWriter() {
                    @Override public void prepare() { throw new ConfigurationException("Cannot write generation report"); }
                    @Override public void write(GenerationReport report) {}
                };
        BsonPayload template = defaultTemplate();
        TemplateCatalogFactory catalogs = (ignored, collections, max) -> new TemplateCatalog() {
            public long count(String collection) { return 1; }
            public long bytes(String collection) { return template.size()+12; }
            public boolean truncated(String collection) { return false; }
            public BsonPayload get(String collection,long ordinal) { return template; }
            public void close() { }
        };
        GenerationOrchestrator service = new GenerationOrchestrator(new FakeSource(template), target, () -> spec(1),
                catalogs, new MongoGenerationBsonEngine(), writer, () -> false);

        GenerationReport report = service.generate(command(true));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.stage()).isEqualTo("VALIDATE_CONFIGURATION");
            assertThat(issue.failureKind()).isEqualTo(FailureKind.CONFIGURATION);
        });
        assertThat(target.written).isZero();
    }

    @Test void coverageDryRunUsesWritePhaseBatchCapacityAndUniquePath() {
        FakeTarget target = new FakeTarget();
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 4, 2, 10, 2,
                Map.of(), List.of(new CollectionGenerationSpec("items", 8, Map.of(
                        "/_id", new RandomString(Alphabet.CUSTOM, "AB", 2, 2, RuleOptions.REQUIRED),
                        "/ordinal", new Sequence(SequenceStart.EXPLICIT, 0, 1, RuleOptions.REQUIRED)))), "hash");
        RecordingEngine engine = new RecordingEngine();
        GenerationOrchestrator service = new GenerationOrchestrator(new FakeSource(defaultTemplate()), target, () -> spec,
                (ignored, collections, max) -> catalogOf(defaultTemplate()), engine, ignored -> {}, () -> false);

        GenerationReport report = service.generate(command(true));

        assertThat(report.status()).as(report.errors().toString()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(engine.batchSizes).isNotEmpty().containsOnly(4);
        assertThat(engine.batchPaths).containsOnly("/_id");
        assertThat(target.written).isZero();
    }

    private static final class RecordingEngine implements com.dataporter.generation.ports.out.GenerationBsonEngine {
        private final com.dataporter.generation.ports.out.GenerationBsonEngine delegate = new MongoGenerationBsonEngine();
        final List<Integer> batchSizes = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> batchPaths = java.util.Collections.synchronizedList(new ArrayList<>());
        @Override public com.dataporter.generation.domain.TemplateFacts inspect(BsonPayload template) { return delegate.inspect(template); }
        @Override public BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                Map<String, GenerationRule> fields, com.dataporter.generation.domain.ResolvedIdStrategy idStrategy,
                Map<String, BsonPayload> sameIterationDocuments, Map<String, Long> sequenceStarts) {
            return delegate.generate(collection, iteration, seed, template, fields, idStrategy, sameIterationDocuments, sequenceStarts);
        }
        @Override public BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                Map<String, GenerationRule> fields, com.dataporter.generation.domain.ResolvedIdStrategy idStrategy,
                Map<String, BsonPayload> sameIterationDocuments, Map<String, Long> sequenceStarts,
                String batchUniqueRandomStringPath, int batchSize) {
            return delegate.generate(collection, iteration, seed, template, fields, idStrategy, sameIterationDocuments,
                    sequenceStarts, batchUniqueRandomStringPath, batchSize);
        }
        @Override public BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                Map<String, GenerationRule> fields, com.dataporter.generation.domain.ResolvedIdStrategy idStrategy,
                Map<String, BsonPayload> sameIterationDocuments, Map<String, Long> sequenceStarts,
                Map<String, com.dataporter.generation.domain.SharedDateDefinition> sharedDates,
                String batchUniqueRandomStringPath, int batchSize) {
            batchSizes.add(batchSize);
            batchPaths.add(batchUniqueRandomStringPath);
            return delegate.generate(collection, iteration, seed, template, fields, idStrategy, sameIterationDocuments,
                    sequenceStarts, sharedDates, batchUniqueRandomStringPath, batchSize);
        }
        @Override public BsonPayload constraintKey(BsonPayload document, com.dataporter.generation.domain.UniqueConstraint constraint) { return delegate.constraintKey(document, constraint); }
        @Override public void validateScalarId(BsonPayload document, String collection) { delegate.validateScalarId(document, collection); }
    }

    @Test void splitsLogicalBlocksIntoByteBoundedPhysicalBatches() {
        BsonPayload template = encode(new BsonDocument("_id", new BsonObjectId(new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b")))
                .append("big", new BsonString("x".repeat(6000))));
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 500, 1, 8, 1,
                Map.of(), List.of(new CollectionGenerationSpec("items", 1000,
                Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED)))), "hash");
        FakeTarget target = new FakeTarget();
        GenerationOrchestrator service = new GenerationOrchestrator(new FakeSource(template), target, () -> spec,
                (ignored, collections, max) -> catalogOf(template), new MongoGenerationBsonEngine(), ignored -> {}, () -> false);

        GenerationReport report = service.generate(command(false));

        assertThat(report.status()).as(report.errors().toString()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.written).isEqualTo(1000);
        assertThat(target.batchBytes).hasSizeGreaterThan(4)
                .allSatisfy(bytes -> assertThat(bytes).isPositive().isLessThanOrEqualTo(1024L * 1024L));
        assertThat(target.batchSizes).allSatisfy(size -> assertThat(size).isLessThan(500));
    }

    @Test void physicalBatchSplittingDoesNotChangeGeneratedValues() {
        BsonPayload template = encode(new BsonDocument("_id", new BsonObjectId(new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b")))
                .append("big", new BsonString("x".repeat(6000))));
        Map<String, GenerationRule> fields = Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED),
                "/serial", new Sequence(SequenceStart.EXPLICIT, 0, 1, RuleOptions.REQUIRED));
        GenerationSpec split = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 500, 1, 8, 1,
                Map.of(), List.of(new CollectionGenerationSpec("items", 400, fields)), "hash");
        GenerationSpec whole = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 500, 1, 8, 256,
                Map.of(), List.of(new CollectionGenerationSpec("items", 400, fields)), "hash");
        FakeTarget splitTarget = new FakeTarget();
        FakeTarget wholeTarget = new FakeTarget();
        new GenerationOrchestrator(new FakeSource(template), splitTarget, () -> split,
                (ignored, collections, max) -> catalogOf(template), new MongoGenerationBsonEngine(), ignored -> {}, () -> false)
                .generate(command(false));
        new GenerationOrchestrator(new FakeSource(template), wholeTarget, () -> whole,
                (ignored, collections, max) -> catalogOf(template), new MongoGenerationBsonEngine(), ignored -> {}, () -> false)
                .generate(command(false));

        assertThat(splitTarget.payloads).containsExactlyElementsOf(wholeTarget.payloads);
        assertThat(wholeTarget.batchBytes).hasSize(1);
    }

    @Test void rejectsDocumentExceedingTotalInFlightBudgetBeforeWrites() {
        BsonPayload template = encode(new BsonDocument("_id", new BsonObjectId(new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b")))
                .append("big", new BsonString("x".repeat(1024 * 1024 + 64))));
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 1, 1, 8, 1,
                Map.of(), List.of(new CollectionGenerationSpec("items", 1,
                Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED)))), "hash");
        FakeTarget target = new FakeTarget();
        GenerationReport report = new GenerationOrchestrator(new FakeSource(template), target, () -> spec,
                (ignored, collections, max) -> catalogOf(template), new MongoGenerationBsonEngine(), ignored -> {}, () -> false)
                .generate(command(false));
        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(target.written).isZero();
        assertThat(report.errors()).anySatisfy(issue -> assertThat(issue.message()).contains("maxInFlightMegabytes"));

        FakeTarget validateTarget = new FakeTarget();
        GenerationReport validateReport = new GenerationOrchestrator(new FakeSource(template), validateTarget, () -> spec,
                (ignored, collections, max) -> catalogOf(template), new MongoGenerationBsonEngine(), ignored -> {}, () -> false)
                .generate(command(true));
        assertThat(validateReport.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(validateTarget.written).isZero();
        assertThat(validateReport.errors()).anySatisfy(issue -> assertThat(issue.message()).contains("maxInFlightMegabytes"));
    }

    @Test void reportsSnapshotCleanupFailureAsWarning() {
        FakeTarget target = new FakeTarget();
        TemplateCatalog failingClose = new TemplateCatalog() {
            public long count(String collection) { return 1; }
            public long bytes(String collection) { return 16; }
            public boolean truncated(String collection) { return false; }
            public BsonPayload get(String collection, long ordinal) { return defaultTemplate(); }
            public void close() { throw new com.dataporter.shared.error.SourceInspectionException("Template snapshot cleanup failed"); }
        };
        GenerationOrchestrator service = new GenerationOrchestrator(new FakeSource(defaultTemplate()), target, () -> spec(2),
                (ignored, collections, max) -> failingClose, new MongoGenerationBsonEngine(), ignored -> {}, () -> false);

        GenerationReport report = service.generate(command(true));

        assertThat(report.status()).as(report.errors().toString()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning).contains("CLEANUP_SNAPSHOT"));
    }

    @Test void validateOnlyPerformsCoverageWithoutProbeOrAppend() {
        FakeTarget target = new FakeTarget();
        GenerationReport report = service(target, spec(3), true).generate(command(true));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.validateOnly()).isTrue();
        assertThat(report.safeToRetry()).isTrue();
        assertThat(target.writeChecks).isZero();
        assertThat(target.written).isZero();
        assertThat(report.collections().getFirst().written()).isZero();
        assertThat(report.collections().getFirst().snapshotTemplates()).isEqualTo(1);
        assertThat(report.collections().getFirst().snapshotTruncated()).isFalse();
    }

    @Test void warnsWhenIdAnalysisAndValidationUseTruncatedPrefix() {
        FakeTarget target = new FakeTarget();

        GenerationReport report = service(target, spec(1), true, true).generate(command(true));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collections().getFirst().snapshotTemplates()).isEqualTo(1);
        assertThat(report.collections().getFirst().snapshotTruncated()).isTrue();
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("_id analysis", "validate-only coverage", "stored snapshot prefix"));
    }

    @Test void upsertWritesExactRequestedCountAndBecomesUnsafeToRetry() {
        FakeTarget target = new FakeTarget();
        GenerationReport report = service(target, spec(4), false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.written).isEqualTo(4);
        assertThat(report.collections().getFirst().generated()).isEqualTo(4);
        assertThat(report.collections().getFirst().written()).isEqualTo(4);
        assertThat(report.safeToRetry()).isFalse();
    }

    @Test void upsertsDocumentsInOrderedBatchSizeBlocksPerCollection() {
        FakeTarget target = new FakeTarget();
        GenerationReport report = service(target, batchSizeSpec(8, 4, 2), false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.batchSizes).containsExactly(4, 4);
        assertThat(target.written).isEqualTo(8);
        assertThat(report.collections().getFirst().generated()).isEqualTo(8);
        assertThat(report.collections().getFirst().written()).isEqualTo(8);
        assertThat(report.safeToRetry()).isFalse();
    }

    @Test void writesPartialFinalBlockWhenCountIsNotAMultipleOfBatchSize() {
        FakeTarget target = new FakeTarget();
        GenerationReport report = service(target, batchSizeSpec(5, 4, 2), false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.batchSizes).containsExactlyInAnyOrder(4, 1);
        assertThat(target.written).isEqualTo(5);
    }

    @Test void writesSingleBatchWhenCountIsBelowBatchSize() {
        FakeTarget target = new FakeTarget();
        GenerationReport report = service(target, batchSizeSpec(3, 4, 2), false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.batchSizes).containsExactly(3);
        assertThat(target.written).isEqualTo(3);
    }

    @Test void parallelWorkersAggregateExactTotalsWithoutLostUpdates() {
        FakeTarget target = new FakeTarget();
        GenerationReport report = service(target, batchSizeSpec(16, 2, 4), false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.batchSizes).hasSize(8).containsOnly(2);
        assertThat(target.written).isEqualTo(16);
        assertThat(report.collections().getFirst().generated()).isEqualTo(16);
        assertThat(report.collections().getFirst().written()).isEqualTo(16);
    }

    @Test void batchWritesFromDifferentWorkersOverlapInTime() {
        CyclicBarrier overlap = new CyclicBarrier(2);
        FakeTarget target = new FakeTarget() {
            @Override public void upsert(DataBatch batch) {
                try { overlap.await(30, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException("batch writes never overlapped", e); }
                super.upsert(batch);
            }
        };
        GenerationReport report = service(target, batchSizeSpec(16, 2, 2), false).generate(command(false));

        assertThat(report.status()).as(report.errors().toString()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.written).isEqualTo(16);
    }

    @Test void conflictingSecondaryKeyFailsTheBlockBeforeAnyWrite() {
        FakeTarget target = new FakeTarget();
        target.conflicts = true;
        target.constraints = List.of(
                new UniqueConstraint("items", "_id_", List.of("/_id"), false, false, false),
                new UniqueConstraint("items", "code_unique", List.of("/code"), false, false, false));

        GenerationReport report = service(target, generationSpec(4,
                Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED),
                        "/code", new Sequence(SequenceStart.EXPLICIT, 0, 1, RuleOptions.REQUIRED))), false)
                .generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors().getFirst().message())
                .contains("Generated key already exists in target for items.code_unique");
        assertThat(target.written).isZero();
        assertThat(report.safeToRetry()).isTrue();
    }

    @Test void oversizedFirstTemplateFailsBeforeTargetAccess() {
        BsonPayload oversized = new BsonPayload(new byte[1_100_000]);
        FakeSource source = new FakeSource(oversized);
        FakeTarget target = new FakeTarget();
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 2, 2, 1, 2,
                List.of(new CollectionGenerationSpec("items", 1,
                        Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED)))), "hash");
        GenerationOrchestrator service = new GenerationOrchestrator(source, target, () -> spec,
                new FileTemplateCatalogFactory(), new MongoGenerationBsonEngine(), ignored -> {}, () -> false);

        GenerationReport report = service.generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors().getFirst().stage()).isEqualTo("SNAPSHOT_TEMPLATES");
        assertThat(report.errors().getFirst().message()).contains("first template");
        assertThat(target.connections).isZero();
        assertThat(target.collectionInspections).isZero();
        assertThat(target.writeChecks).isZero();
    }

    @Test void acceptsBatchUniqueRandomStringIdAndWarnsWhenFullCountExceedsIdSpace() {
        FakeTarget target = new FakeTarget();
        GenerationRule id = new RandomString(Alphabet.CUSTOM, "AB", 2, 2, RuleOptions.REQUIRED);
        GenerationSpec valid = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 4, 2, 10, 2,
                List.of(new CollectionGenerationSpec("items", 8, Map.of(
                        "/_id", id,
                        "/ordinal", new Sequence(SequenceStart.EXPLICIT, 0, 1, RuleOptions.REQUIRED)))), "hash");

        GenerationReport generated = service(target, valid, false).generate(command(false));

        assertThat(generated.status()).isEqualTo(OperationStatus.SUCCESS);
        List<String> ids = target.payloads.stream().map(payload -> new RawBsonDocument(payload.bytes()))
                .sorted(Comparator.comparingLong(document -> document.getInt64("ordinal").getValue()))
                .map(document -> document.getString("_id").getValue()).toList();
        assertThat(ids.subList(0, 4)).doesNotHaveDuplicates();
        assertThat(ids.subList(4, 8)).doesNotHaveDuplicates();

        FakeTarget invalidTarget = new FakeTarget();
        GenerationSpec invalid = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 3, 2, 10, 2,
                List.of(new CollectionGenerationSpec("items", 3, Map.of("/_id",
                        new RandomString(Alphabet.CUSTOM, "AB", 1, 1, RuleOptions.REQUIRED)))), "hash");
        GenerationReport collisionProne = service(invalidTarget, invalid, false).generate(command(false));

        assertThat(collisionProne.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(collisionProne.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("POSSIBLE _id COLLISIONS", "keyspace=2", "risk=guaranteed"));
        assertThat(invalidTarget.written).isEqualTo(3);
    }

    @Test void alphaNumRangeIdWarnsWithoutBatchUniquenessAndDoesNotProveSecondaryUniqueness() {
        RandomAlphaNumStringBetween range = new RandomAlphaNumStringBetween(
                BigInteger.valueOf(2_000_000), BigInteger.valueOf(2_000_002), 6, RuleOptions.REQUIRED);
        FakeTarget idTarget = new FakeTarget();

        GenerationReport generated = service(idTarget, generationSpec(5, Map.of("/_id", range)), false)
                .generate(command(false));

        assertThat(generated.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(generated.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("POSSIBLE _id COLLISIONS", "keyspace=2", "risk=guaranteed"));
        List<String> generatedIds = idTarget.payloads.stream().map(payload ->
                new RawBsonDocument(payload.bytes()).getString("_id").getValue()).toList();
        assertThat(generatedIds).hasSize(5).allMatch(value -> value.matches("[0-9A-Z]{6}"));
        assertThat(new HashSet<>(generatedIds)).hasSizeLessThan(generatedIds.size());

        FakeTarget secondaryTarget = new FakeTarget();
        secondaryTarget.constraints = List.of(
                new UniqueConstraint("items", "_id_", List.of("/_id"), false, false, false),
                new UniqueConstraint("items", "code_unique", List.of("/code"), false, false, false));
        GenerationReport secondary = service(secondaryTarget, generationSpec(2, Map.of(
                "/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED), "/code", range)), false)
                .generate(command(false));

        assertThat(secondary.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(secondary.errors().getFirst().message()).contains("code_unique");
        assertThat(secondaryTarget.written).isZero();
    }

    @Test void weightedRandomRulesProduceScalarIdsWithConservativeCollisionWarning() {
        WeightedChoice id = new WeightedChoice(List.of(
                new Choice(new RandomAlphaNumStringBetween(BigInteger.ZERO, BigInteger.TWO, 2,
                        RuleOptions.REQUIRED), 1),
                new Choice(new RandomAlphaNumStringBetween(BigInteger.valueOf(36), BigInteger.valueOf(38), 2,
                        RuleOptions.REQUIRED), 1)), RuleOptions.REQUIRED);
        FakeTarget target = new FakeTarget();

        GenerationReport generated = service(target, generationSpec(8, Map.of("/_id", id)), false)
                .generate(command(false));

        assertThat(generated.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(generated.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("POSSIBLE _id COLLISIONS", "keyspace=unknown", "risk=unknown"));
        assertThat(target.payloads).extracting(payload -> new RawBsonDocument(payload.bytes()).getString("_id").getValue())
                .allMatch(value -> value.matches("0[01]|1[01]"));

        WeightedChoice partlyStatic = new WeightedChoice(List.of(
                new Choice("fixed", 1),
                new Choice(new RandomAlphaNumStringBetween(BigInteger.ZERO, BigInteger.TWO, 2,
                        RuleOptions.REQUIRED), 1)), RuleOptions.REQUIRED);
        FakeTarget rejectedTarget = new FakeTarget();
        GenerationReport rejected = service(rejectedTarget,
                generationSpec(2, Map.of("/_id", partlyStatic)), false).generate(command(false));

        assertThat(rejected.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(rejected.errors().getFirst().message()).contains("Cannot prove generated uniqueness");
        assertThat(rejectedTarget.written).isZero();
    }

    @Test void rejectsNonRequiredOrNonScalarWeightedIdBranchBeforeWriting() {
        List<GenerationRule> invalidBranches = List.of(
                new RandomString(Alphabet.UPPER_LATIN, null, 2, 2, new RuleOptions(.1, 0)),
                new ObjectValue(Map.of("value", new Literal("x", RuleOptions.REQUIRED)), RuleOptions.REQUIRED));

        for (GenerationRule branch : invalidBranches) {
            FakeTarget target = new FakeTarget();
            WeightedChoice id = new WeightedChoice(List.of(new Choice(branch, 1)), RuleOptions.REQUIRED);
            GenerationReport report = service(target, generationSpec(1, Map.of("/_id", id)), false)
                    .generate(command(false));

            assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
            assertThat(target.written).isZero();
        }
    }

    @Test void acceptsCompositeIdWithGeneratedRandomStringAndTemplateComponents() {
        FakeTarget target = new FakeTarget();
        LinkedHashMap<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/generated", new RandomString(Alphabet.CUSTOM, "AB", 2, 2, RuleOptions.REQUIRED));
        fields.put("/_id", new Concat(List.of(
                new Ref(null, "/generated", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Literal("|", RuleOptions.REQUIRED),
                new Ref(null, "/staticValue", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED));
        GenerationSpec composite = generationSpec(4, fields);

        GenerationReport report = service(target, composite, false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collections().getFirst().idStrategy().kind().name()).isEqualTo("EXPLICIT");
        assertThat(target.payloads).extracting(payload -> new RawBsonDocument(payload.bytes()).getString("_id").getValue())
                .allMatch(id -> id.endsWith("|kept")).doesNotHaveDuplicates();
    }

    @Test void acceptsRandomComponentAnywhereInCompositeIdAndWarnsAboutRisk() {
        FakeTarget target = new FakeTarget();
        GenerationRule id = new Concat(List.of(
                new Ref(null, "/staticValue", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new RandomString(Alphabet.UPPER_LATIN, null, 4, 4, RuleOptions.REQUIRED)), RuleOptions.REQUIRED);

        GenerationReport report = service(target, generationSpec(1, Map.of("/_id", id)), false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("POSSIBLE _id COLLISIONS"));
        assertThat(target.written).isEqualTo(1);
    }

    @Test void preservesLegacyUniqueConcatIdBehavior() {
        FakeTarget target = new FakeTarget();
        LinkedHashMap<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/sequence", new Sequence(SequenceStart.EXPLICIT, 10, 1, RuleOptions.REQUIRED));
        fields.put("/_id", new Concat(List.of(new Literal("ORD-", RuleOptions.REQUIRED),
                new Ref(null, "/sequence", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED));

        GenerationReport report = service(target, generationSpec(2, fields), false).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(target.payloads).extracting(payload -> new RawBsonDocument(payload.bytes()).getString("_id").getValue())
                .containsExactlyInAnyOrder("ORD-10", "ORD-11");
    }

    @Test void acceptsVariableRandomIdWithUnknownRiskButRejectsNullableOrMissingComponents() {
        GenerationRule variable = new Concat(List.of(
                new RandomString(Alphabet.UPPER_LATIN, null, 2, 3, RuleOptions.REQUIRED)), RuleOptions.REQUIRED);
        GenerationReport accepted = service(new FakeTarget(), generationSpec(2, Map.of("/_id", variable)), false)
                .generate(command(false));
        assertThat(accepted.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(accepted.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("POSSIBLE _id COLLISIONS", "keyspace=unknown", "risk=unknown"));

        List<GenerationRule> invalidIds = List.of(
                new Concat(List.of(new RandomString(Alphabet.UPPER_LATIN, null, 3, 3, new RuleOptions(.1, 0))), RuleOptions.REQUIRED),
                new Concat(List.of(new RandomString(Alphabet.UPPER_LATIN, null, 3, 3, RuleOptions.REQUIRED),
                        new Ref(null, "/staticValue", MissingPolicy.NULL, RuleOptions.REQUIRED)), RuleOptions.REQUIRED));

        for (GenerationRule id : invalidIds) {
            FakeTarget target = new FakeTarget();
            GenerationReport report = service(target, generationSpec(2, Map.of("/_id", id)), false).generate(command(false));
            assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
            assertThat(target.writeChecks).isZero();
            assertThat(target.written).isZero();
        }
    }

    @Test void unprovenScalarIdRequiresFlagAndEffectiveLiteralStillFailsWithFlag() {
        GenerationRule unproven = new WeightedChoice(List.of(
                new Choice("same", 1)), RuleOptions.REQUIRED);

        GenerationReport strict = service(new FakeTarget(), generationSpec(2, Map.of("/_id", unproven)), false)
                .generate(command(false));
        assertThat(strict.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(strict.errors().getFirst().message()).contains("Cannot prove generated uniqueness");

        FakeTarget allowedTarget = new FakeTarget();
        List<String> events = new ArrayList<>();
        allowedTarget.events = events;
        GenerationOrchestrator allowedService = service(allowedTarget, generationSpec(2, Map.of("/_id", unproven)), false,
                false, defaultTemplate(), (generationId, warning) -> events.add("warning:" + warning));
        GenerationReport allowed = allowedService.generate(command(false, true));
        assertThat(allowed.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(allowed.allowUnprovenIds()).isTrue();
        assertThat(allowed.warnings()).anyMatch(warning -> warning.contains("ID UNIQUENESS PROOF DISABLED"));
        assertThat(events.getFirst()).startsWith("warning:ID UNIQUENESS PROOF DISABLED");
        assertThat(events).contains("write");

        for (GenerationRule literal : List.of(
                new Literal("fixed", RuleOptions.REQUIRED),
                new Concat(List.of(new Literal("a", RuleOptions.REQUIRED),
                        new Literal("b", RuleOptions.REQUIRED)), RuleOptions.REQUIRED),
                new Ref(null, "/fixed", MissingPolicy.ERROR, RuleOptions.REQUIRED))) {
            Map<String,GenerationRule> fields = new LinkedHashMap<>();
            fields.put("/fixed", new Literal("fixed", RuleOptions.REQUIRED));
            fields.put("/_id", literal);
            GenerationReport report = service(new FakeTarget(), generationSpec(1, fields), false)
                    .generate(command(false, true));
            assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        }
    }

    @Test void allowUnprovenIdsDoesNotRelaxSecondaryUniqueOrUnsupportedIndexChecks() {
        GenerationRule unproven = new WeightedChoice(List.of(new Choice("same", 1)), RuleOptions.REQUIRED);
        GenerationSpec spec = generationSpec(2, Map.of("/_id", unproven, "/code", new Literal("x", RuleOptions.REQUIRED)));

        FakeTarget secondary = new FakeTarget();
        secondary.constraints = List.of(
                new UniqueConstraint("items", "_id_", List.of("/_id"), false, false, false),
                new UniqueConstraint("items", "code_unique", List.of("/code"), false, false, false));
        GenerationReport secondaryReport = service(secondary, spec, false).generate(command(false, true));
        assertThat(secondaryReport.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(secondaryReport.errors().getFirst().message()).contains("code_unique");
        assertThat(secondary.written).isZero();

        FakeTarget unsupported = new FakeTarget();
        unsupported.constraints = List.of(new UniqueConstraint("items", "_id_", List.of("/_id"), true, false, false));
        GenerationReport unsupportedReport = service(unsupported, spec, false).generate(command(false, true));
        assertThat(unsupportedReport.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(unsupportedReport.errors().getFirst().message()).contains("unsupported");
        assertThat(unsupported.written).isZero();
    }

    @Test void compositeIdTemplateCoverageRejectsNullObjectAndArrayBeforeWriteProbe() {
        List<BsonDocument> invalidTemplates = new ArrayList<>();
        invalidTemplates.add(new BsonDocument("_id", new BsonInt32(1)));
        for (BsonValue invalid : List.of(BsonNull.VALUE, new BsonDocument("x", new BsonInt32(1)),
                new BsonArray(List.of(new BsonInt32(1)))))
            invalidTemplates.add(new BsonDocument("_id", new BsonInt32(1)).append("component", invalid));
        for (BsonDocument invalidTemplate : invalidTemplates) {
            BsonPayload template = encode(invalidTemplate);
            FakeTarget target = new FakeTarget();
            GenerationRule id = new Concat(List.of(
                    new RandomString(Alphabet.UPPER_LATIN, null, 3, 3, RuleOptions.REQUIRED),
                    new Ref(null, "/component", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED);

            GenerationReport report = service(target, generationSpec(1, Map.of("/_id", id)), false,
                    false, template).generate(command(false));

            assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
            assertThat(report.errors().getFirst().stage()).isEqualTo("VALIDATE_GENERATION_RULES");
            assertThat(target.writeChecks).isZero();
            assertThat(target.written).isZero();
        }
    }

    @Test void unprovenDirectTemplateRefStillRequiresPresentScalarIdBeforeWriteProbe() {
        for (BsonDocument invalidTemplate : List.of(
                new BsonDocument("_id", new BsonInt32(1)),
                new BsonDocument("_id", new BsonInt32(1)).append("candidate", BsonNull.VALUE),
                new BsonDocument("_id", new BsonInt32(1)).append("candidate", new BsonDocument("x", new BsonInt32(1))),
                new BsonDocument("_id", new BsonInt32(1)).append("candidate", new BsonArray()))) {
            FakeTarget target = new FakeTarget();
            GenerationRule id = new Ref(null, "/candidate", MissingPolicy.ERROR, RuleOptions.REQUIRED);

            GenerationReport report = service(target, generationSpec(1, Map.of("/_id", id)), false,
                    false, encode(invalidTemplate)).generate(command(false, true));

            assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
            assertThat(report.errors().getFirst().stage()).isEqualTo("VALIDATE_GENERATION_RULES");
            assertThat(target.writeChecks).isZero();
            assertThat(target.written).isZero();
        }
    }

    @Test void parallelCoverageReportsProgressPerChunkInIterationOrder() {
        List<String> events = new ArrayList<>();
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 2, 2, 10, 2,
                List.of(new CollectionGenerationSpec("items", 8,
                        Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED)))), "hash");

        GenerationReport report = service(new FakeTarget(), ordinalCatalog(8, ignored -> defaultTemplate()), spec,
                capturing(events)).generate(command(true));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(events).containsExactly(
                "VALIDATE_GENERATION_RULES:2/8", "VALIDATE_GENERATION_RULES:4/8",
                "VALIDATE_GENERATION_RULES:6/8", "VALIDATE_GENERATION_RULES:8/8");
    }

    @Test void parallelCoverageFailsOnTheSameFirstFailingIterationAsSequentialRuns() {
        BsonPayload withComponent = encode(new BsonDocument(
                "_id", new BsonObjectId(new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b")))
                .append("component", new BsonString("c")));
        BsonPayload withoutComponent = encode(new BsonDocument(
                "_id", new BsonObjectId(new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b"))));
        TemplateCatalog catalog = ordinalCatalog(8, ordinal -> ordinal < 6 ? withComponent : withoutComponent);
        GenerationRule id = new Concat(List.of(
                new RandomString(Alphabet.UPPER_LATIN, null, 3, 3, RuleOptions.REQUIRED),
                new Ref(null, "/component", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED);
        GenerationSpec spec = new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 2, 2, 10, 2,
                List.of(new CollectionGenerationSpec("items", 8, Map.of("/_id", id))), "hash");
        FakeTarget target = new FakeTarget();

        GenerationReport report = service(target, catalog, spec, capturing(new ArrayList<>())).generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors().getFirst().stage()).isEqualTo("VALIDATE_GENERATION_RULES");
        assertThat(report.errors().getFirst().message()).contains("Missing referenced value /component");
        assertThat(target.writeChecks).isZero();
        assertThat(target.written).isZero();
    }

    @Test void writePhaseReportsDocumentProgressPerCompletedBlock() {
        List<String> events = new ArrayList<>();
        FakeTarget target = new FakeTarget();

        GenerationReport report = service(target, catalogOf(defaultTemplate()), batchSizeSpec(8, 4, 2), capturing(events))
                .generate(command(false));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(events).containsExactly(
                "VALIDATE_GENERATION_RULES:1/1",
                "GENERATE_AND_APPEND:4/8", "GENERATE_AND_APPEND:8/8");
    }

    private GenerationOrchestrator service(FakeTarget target, GenerationSpec spec, boolean validateOnly) {
        return service(target, spec, validateOnly, false);
    }

    private GenerationOrchestrator service(FakeTarget target, GenerationSpec spec, boolean validateOnly, boolean truncated) {
        BsonPayload template = defaultTemplate();
        return service(target, spec, validateOnly, truncated, template);
    }

    private GenerationOrchestrator service(FakeTarget target, GenerationSpec spec, boolean validateOnly,
                                      boolean truncated, BsonPayload template) {
        FakeSource source = new FakeSource(template);
        TemplateCatalogFactory catalogs = (ignored, collections, max) -> new TemplateCatalog() {
            public long count(String collection) { return 1; }
            public long bytes(String collection) { return template.size()+12; }
            public boolean truncated(String collection) { return truncated; }
            public BsonPayload get(String collection,long ordinal) { return template; }
            public void close() { }
        };
        return new GenerationOrchestrator(source, target, () -> spec, catalogs, new MongoGenerationBsonEngine(), ignored -> {}, () -> false);
    }
    private GenerationOrchestrator service(FakeTarget target, GenerationSpec spec, boolean validateOnly,
                                      boolean truncated, BsonPayload template,
                                      com.dataporter.generation.ports.out.GenerationProgressReporter progress) {
        FakeSource source = new FakeSource(template);
        TemplateCatalogFactory catalogs = (ignored, collections, max) -> new TemplateCatalog() {
            public long count(String collection) { return 1; }
            public long bytes(String collection) { return template.size()+12; }
            public boolean truncated(String collection) { return truncated; }
            public BsonPayload get(String collection,long ordinal) { return template; }
            public void close() { }
        };
        return new GenerationOrchestrator(source, target, () -> spec, catalogs, new MongoGenerationBsonEngine(),
                ignored -> {}, progress, () -> false);
    }
    private BsonPayload defaultTemplate() {
        return encode(new BsonDocument("_id", new BsonObjectId(new org.bson.types.ObjectId("64b64b64b64b64b64b64b64b")))
                .append("staticValue", new BsonString("kept")));
    }

    private GenerationOrchestrator service(FakeTarget target, TemplateCatalog catalog, GenerationSpec spec,
                                      com.dataporter.generation.ports.out.GenerationProgressReporter progress) {
        return new GenerationOrchestrator(new FakeSource(defaultTemplate()), target, () -> spec,
                (ignored, collections, max) -> catalog, new MongoGenerationBsonEngine(), ignored -> {}, progress, () -> false);
    }

    private TemplateCatalog catalogOf(BsonPayload template) {
        return ordinalCatalog(1, ignored -> template);
    }

    private TemplateCatalog ordinalCatalog(long count, java.util.function.LongFunction<BsonPayload> template) {
        return new TemplateCatalog() {
            public long count(String collection) { return count; }
            public long bytes(String collection) { return count * (template.apply(0).size() + 12); }
            public boolean truncated(String collection) { return false; }
            public BsonPayload get(String collection, long ordinal) { return template.apply(ordinal); }
            public void close() { }
        };
    }

    private com.dataporter.generation.ports.out.GenerationProgressReporter capturing(List<String> events) {
        return new com.dataporter.generation.ports.out.GenerationProgressReporter() {
            @Override public void warning(String generationId, String warning) { }
            @Override public void progress(String generationId, String stage, long completed, long total) {
                events.add(stage + ":" + completed + "/" + total);
            }
        };
    }
    private GenerationSpec spec(long count) {
        Map<String,GenerationRule> fields = Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED));
        return new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 2, 2, 10, 2,
                List.of(new CollectionGenerationSpec("items", count, fields)), "hash");
    }
    private GenerationSpec batchSizeSpec(long count, int batchSize, int parallelism) {
        Map<String,GenerationRule> fields = Map.of("/_id", new GenerationRule.ObjectId(RuleOptions.REQUIRED));
        return new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, batchSize, parallelism, 10, 2,
                List.of(new CollectionGenerationSpec("items", count, fields)), "hash");
    }
    private GenerationSpec generationSpec(long count, Map<String,GenerationRule> fields) {
        return new GenerationSpec(1, 123L, TemplateSelection.SHUFFLED_CYCLE, 4, 2, 10, 2,
                List.of(new CollectionGenerationSpec("items", count, fields)), "hash");
    }
    private GenerationCommand command(boolean validateOnly) {
        return command(validateOnly, false);
    }
    private GenerationCommand command(boolean validateOnly, boolean allowUnprovenIds) {
        Endpoint endpoint = new Endpoint("mongodb://same:27017", "catalog");
        return new GenerationCommand(endpoint, endpoint, new GenerationOptions(validateOnly, allowUnprovenIds));
    }
    private BsonPayload encode(BsonDocument document) {
        RawBsonDocument raw = new RawBsonDocument(document, new BsonDocumentCodec());
        return new BsonPayload(Arrays.copyOfRange(raw.getBackingArray(), raw.getByteOffset(), raw.getByteOffset()+raw.getByteLength()));
    }

    private static final class FakeSource implements GenerationSource {
        final BsonPayload template;
        FakeSource(BsonPayload template){this.template=template;}
        public void checkConnection(){}
        public void checkReadable(){}
        public boolean databaseExists(){return true;}
        public GenerationSourceInspection inspect(){return new GenerationSourceInspection(List.of("items"),List.of());}
        public BatchCursor openBatches(String collection,int size){return new BatchCursor(){boolean used;
            public DataBatch next(){if(used)return null;used=true;return new DataBatch(collection,List.of(template),template.size());}
            public void close(){}};}
        public void close(){}
    }
    private static class FakeTarget implements GenerationTarget {
        int connections,collectionInspections,writeChecks,written;
        final List<BsonPayload> payloads = new ArrayList<>();
        final List<Integer> batchSizes = new ArrayList<>();
        final List<Long> batchBytes = new ArrayList<>();
        List<UniqueConstraint> constraints = List.of(new UniqueConstraint("items","_id_",List.of("/_id"),false,false,false));
        List<String> events = new ArrayList<>();
        boolean conflicts;
        public void checkConnection(){connections++;}
        public void checkWritable(){writeChecks++;}
        public Set<String> ordinaryCollections(){collectionInspections++;return Set.of("items");}
        public List<UniqueConstraint> uniqueConstraints(String collection){return constraints;}
        public long nextSequenceStart(String collection,String path,long step){return 1;}
        public boolean constraintKeyConflicts(UniqueConstraint constraint,List<BsonPayload> keys,List<BsonPayload> idKeys){return conflicts;}
        public synchronized void upsert(DataBatch batch){events.add("write");written+=batch.documents().size();payloads.addAll(batch.documents());batchSizes.add(batch.documents().size());batchBytes.add(batch.bytes());}
        public void close(){}
    }
}
