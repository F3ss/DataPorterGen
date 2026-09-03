package com.dataporter.adapters.mongo;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.migration.domain.VerificationResult;
import com.dataporter.migration.domain.merge.MergeCollectionSummary;
import com.dataporter.migration.domain.merge.MergeFingerprint;
import com.dataporter.migration.domain.merge.MergeVerificationContext;
import com.dataporter.migration.ports.out.DatabaseReader;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.ports.out.BatchCursor;

import org.bson.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class MongoMigrationVerifierTest {
    @Test
    void mergeFullMatchesSourceBytesByBatchAndAllowsExtraTargetDocuments() {
        BsonPayload first = document(1, "source-one");
        BsonPayload second = document(2, "source-two");
        MigrationPlan expected = plan("customers");
        MigrationPlan actual = new MigrationPlan(List.of(
                expected.collections().getFirst(),
                new CollectionDefinition("target_only", BsonPayload.emptyArray())), List.of(), List.of());
        FakeReader source = new FakeReader(expected, Map.of("customers", List.of(first, second)));
        FakeReader target = new FakeReader(actual, Map.of("customers",
                List.of(first, second, document(99, "target-only"))));
        MergeFingerprint.Accumulator expectedFingerprint = MergeFingerprint.accumulator();
        expectedFingerprint.addBatch(MergeFingerprint.batch(List.of(first, second)));
        MergeCollectionSummary summary = new MergeCollectionSummary(1, 2, 2, 0, 0,
                first.size() + second.size(), expectedFingerprint.finish());

        VerificationResult result = new MongoMigrationVerifier(source, target, 2).verifyMerge(expected,
                VerificationLevel.FULL, new MergeVerificationContext(Map.of("customers", summary)));

        assertThat(result.successful()).isTrue();
        assertThat(result.differences()).isEmpty();
        assertThat(target.lookupBatchSizes).containsExactly(2);
    }

    @Test
    void mergeFullReportsMissingSourceId() {
        BsonPayload first = document(1, "source-one");
        BsonPayload second = document(2, "source-two");
        MigrationPlan plan = plan("customers");
        FakeReader source = new FakeReader(plan, Map.of("customers", List.of(first, second)));
        FakeReader target = new FakeReader(plan, Map.of("customers", List.of(first)));
        MergeCollectionSummary summary = new MergeCollectionSummary(1, 2, 2, 0, 0,
                first.size() + second.size());

        VerificationResult result = new MongoMigrationVerifier(source, target, 2).verifyMerge(plan,
                VerificationLevel.FULL, new MergeVerificationContext(Map.of("customers", summary)));

        assertThat(result.successful()).isFalse();
        assertThat(result.differences()).anySatisfy(difference ->
                assertThat(difference).contains("missing source _id"));
    }

    @Test
    void mergeFullReportsContentDifference() {
        BsonPayload sourceDocument = document(1, "source");
        BsonPayload different = document(1, "different");
        MigrationPlan plan = plan("customers");
        FakeReader source = new FakeReader(plan, Map.of("customers", List.of(sourceDocument)));
        FakeReader target = new FakeReader(plan, Map.of("customers", List.of(
                different, document(2, "existing"), document(3, "inserted"))));
        MergeCollectionSummary summary = new MergeCollectionSummary(2, 1, 1, 0, 0,
                sourceDocument.size());

        VerificationResult metadata = new MongoMigrationVerifier(source, target, 2).verifyMerge(plan,
                VerificationLevel.METADATA_AND_COUNTS,
                new MergeVerificationContext(Map.of("customers", summary)));
        VerificationResult full = new MongoMigrationVerifier(source, target, 2).verifyMerge(plan,
                VerificationLevel.FULL,
                new MergeVerificationContext(Map.of("customers", summary)));

        assertThat(metadata.successful()).isTrue();
        assertThat(full.successful()).isFalse();
        assertThat(full.differences()).anySatisfy(difference ->
                assertThat(difference).contains("BSON content differs"));
    }

    @Test
    void mergeFullFingerprintDetectsSummaryDivergence() {
        BsonPayload first = document(1, "source-one");
        BsonPayload second = document(2, "source-two");
        MigrationPlan plan = plan("customers");
        FakeReader source = new FakeReader(plan, Map.of("customers", List.of(first, second)));
        FakeReader target = new FakeReader(plan, Map.of("customers", List.of(first, second)));
        MergeCollectionSummary summary = new MergeCollectionSummary(0, 2, 2, 0, 0,
                first.size() + second.size(), MergeFingerprint.batch(List.of(document(1, "other"))));

        VerificationResult result = new MongoMigrationVerifier(source, target, 2).verifyMerge(plan,
                VerificationLevel.FULL, new MergeVerificationContext(Map.of("customers", summary)));

        assertThat(result.successful()).isFalse();
        assertThat(result.differences()).contains("customers: MERGE target-state fingerprint differs");
    }

    private static MigrationPlan plan(String collection) {
        return new MigrationPlan(List.of(new CollectionDefinition(collection, BsonPayload.emptyArray())),
                List.of(), List.of());
    }

    private static BsonPayload document(int id, String value) {
        return MongoBson.encode(new BsonDocument("_id", new BsonInt32(id))
                .append("value", new BsonString(value)));
    }

    private static final class FakeReader implements DatabaseReader {
        private final MigrationPlan plan;
        private final Map<String, List<BsonPayload>> documents;
        final List<Integer> lookupBatchSizes = new ArrayList<>();

        private FakeReader(MigrationPlan plan, Map<String, List<BsonPayload>> documents) {
            this.plan = plan;
            this.documents = documents;
        }

        public void checkConnection() {}
        public boolean databaseExists() { return true; }
        public MigrationPlan inspect() { return plan; }
        public BatchCursor openBatches(String collection, int batchSize) {
            return new BatchCursor() {
                boolean consumed;
                public DataBatch next() {
                    if (consumed) return null;
                    consumed = true;
                    List<BsonPayload> batch = documents.getOrDefault(collection, List.of());
                    return new DataBatch(collection, batch, batch.stream().mapToLong(BsonPayload::size).sum());
                }
                public void close() {}
            };
        }
        public long count(String collection) { return documents.getOrDefault(collection, List.of()).size(); }
        public List<BsonPayload> findManyBySourceDocuments(String collection, List<BsonPayload> sourceDocuments) {
            lookupBatchSizes.add(sourceDocuments.size());
            List<BsonValue> ids = sourceDocuments.stream()
                    .map(payload -> MongoBson.decode(payload).get("_id")).toList();
            return documents.getOrDefault(collection, List.of()).stream()
                    .filter(payload -> ids.contains(MongoBson.decode(payload).get("_id")))
                    .toList();
        }
        public void close() {}
    }
}
