package com.dataporter.adapters.mongo;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.migration.domain.VerificationResult;
import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.migration.domain.merge.MergeCollectionSummary;
import com.dataporter.migration.domain.merge.MergeFingerprint;
import com.dataporter.migration.domain.merge.MergeVerificationContext;
import com.dataporter.migration.ports.out.DatabaseReader;
import com.dataporter.migration.ports.out.MigrationVerifier;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.ports.out.BatchCursor;

import org.bson.BsonValue;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MongoMigrationVerifier implements MigrationVerifier {
    private final DatabaseReader source;
    private final DatabaseReader target;
    private final int batchSize;

    public MongoMigrationVerifier(DatabaseReader source, DatabaseReader target, int batchSize) {
        this.source = source;
        this.target = target;
        this.batchSize = batchSize;
    }

    public VerificationResult verify(MigrationPlan expected, VerificationLevel level) {
        List<String> differences = new ArrayList<>();
        MigrationPlan actual = target.inspect();
        compareCollections(expected, actual, differences);
        compareIndexes(expected, actual, differences);
        compareViews(expected, actual, differences);
        for (CollectionDefinition collection : expected.collections()) {
            long sourceCount = source.count(collection.name());
            long targetCount = target.count(collection.name());
            if (sourceCount != targetCount)
                differences.add(collection.name() + ": document count " + sourceCount + " != " + targetCount);
            if (level == VerificationLevel.FULL && sourceCount == targetCount)
                compareDocuments(collection.name(), differences);
        }
        return new VerificationResult(differences.isEmpty(), differences);
    }

    @Override
    public VerificationResult verifyMerge(MigrationPlan expected, VerificationLevel level,
                                          MergeVerificationContext context) {
        List<String> differences = new ArrayList<>();
        MigrationPlan actual = target.inspect();
        compareCollections(expected, actual, differences);
        compareIndexes(expected, actual, differences);
        compareViews(expected, actual, differences);
        for (CollectionDefinition collection : expected.collections()) {
            MergeCollectionSummary summary = context.collections().get(collection.name());
            if (summary == null) {
                differences.add(collection.name() + ": missing MERGE action summary");
                continue;
            }
            long sourceCount = source.count(collection.name());
            if (sourceCount != summary.sourceDocuments())
                differences.add(collection.name() + ": inspected source count " + summary.sourceDocuments()
                        + " != current source count " + sourceCount);
            long expectedTargetCount = summary.initialTargetDocuments() + summary.insertedDocuments();
            long targetCount = target.count(collection.name());
            if (targetCount != expectedTargetCount)
                differences.add(collection.name() + ": MERGE document count " + targetCount
                        + " != expected " + expectedTargetCount);
            if (level == VerificationLevel.FULL)
                compareMergeDocuments(collection.name(), summary, differences);
        }
        return new VerificationResult(differences.isEmpty(), differences);
    }

    private void compareCollections(MigrationPlan expected, MigrationPlan actual, List<String> differences) {
        Map<String, CollectionDefinition> actualByName = actual.collections().stream()
                .collect(Collectors.toMap(CollectionDefinition::name, Function.identity()));
        for (CollectionDefinition collection : expected.collections()) {
            CollectionDefinition found = actualByName.get(collection.name());
            if (found == null) differences.add("Missing collection: " + collection.name());
            else if (!MongoMetadata.collectionEquivalent(collection.options(), found.options()))
                differences.add("Collection options differ: " + collection.name());
        }
    }

    private void compareIndexes(MigrationPlan expected, MigrationPlan actual, List<String> differences) {
        Map<String, IndexDefinition> actualByName = actual.indexes().stream()
                .collect(Collectors.toMap(i -> i.collection() + "\0" + i.name(), Function.identity()));
        for (IndexDefinition index : expected.indexes()) {
            IndexDefinition found = actualByName.get(index.collection() + "\0" + index.name());
            if (found == null) differences.add("Missing index: " + index.collection() + "." + index.name());
            else if (!MongoMetadata.indexEquivalent(index.specification(), found.specification()))
                differences.add("Index options differ: " + index.collection() + "." + index.name());
        }
    }

    private void compareViews(MigrationPlan expected, MigrationPlan actual, List<String> differences) {
        Map<String, ViewDefinition> actualByName = actual.views().stream()
                .collect(Collectors.toMap(ViewDefinition::name, Function.identity()));
        for (ViewDefinition view : expected.views()) {
            ViewDefinition found = actualByName.get(view.name());
            if (found == null) differences.add("Missing view: " + view.name());
            else if (!view.viewOn().equals(found.viewOn())
                    || !MongoMetadata.viewEquivalent(view.options(), found.options()))
                differences.add("View definition differs: " + view.name());
        }
    }

    private void compareDocuments(String collection, List<String> differences) {
        try (BatchCursor left = source.openBatches(collection, batchSize);
             BatchCursor right = target.openBatches(collection, batchSize)) {
            long position = 0;
            while (true) {
                DataBatch a = left.next(), b = right.next();
                if (a == null || b == null) {
                    if (a != b) differences.add(collection + ": document streams have different lengths");
                    return;
                }
                if (!a.documents().equals(b.documents())) {
                    differences.add(collection + ": BSON content differs near document " + position);
                    return;
                }
                position += a.documents().size();
            }
        }
    }

    private void compareMergeDocuments(String collection, MergeCollectionSummary summary,
                                       List<String> differences) {
        long position = 0;
        MergeFingerprint.Accumulator fingerprint = MergeFingerprint.accumulator();
        try (BatchCursor cursor = source.openBatches(collection, batchSize)) {
            for (DataBatch batch; (batch = cursor.next()) != null; ) {
                Map<BsonValue, BsonPayload> targetById = new HashMap<>();
                for (BsonPayload targetDocument : target.findManyBySourceDocuments(collection, batch.documents())) {
                    BsonValue id = MongoBson.decode(targetDocument).get("_id");
                    if (id != null) targetById.put(id, targetDocument);
                }
                List<BsonPayload> actualTargetBatch = new ArrayList<>(batch.documents().size());
                for (BsonPayload sourceDocument : batch.documents()) {
                    BsonPayload targetDocument = targetById.get(MongoBson.decode(sourceDocument).get("_id"));
                    if (targetDocument == null) {
                        differences.add(collection + ": missing source _id near document " + position);
                        return;
                    }
                    if (!sourceDocument.equals(targetDocument)) {
                        differences.add(collection + ": BSON content differs near document " + position);
                        return;
                    }
                    actualTargetBatch.add(targetDocument);
                    position++;
                }
                fingerprint.addBatch(MergeFingerprint.batch(actualTargetBatch));
            }
        }
        if (!summary.expectedTargetFingerprint().isBlank()
                && !summary.expectedTargetFingerprint().equals(fingerprint.finish())) {
            differences.add(collection + ": MERGE target-state fingerprint differs");
        }
    }
}
