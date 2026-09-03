package com.dataporter.adapters.mongo;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.migration.domain.error.DocumentMigrationException;
import com.dataporter.migration.domain.error.MetadataMigrationException;
import com.dataporter.migration.domain.merge.MergeBatchResult;
import com.dataporter.migration.domain.merge.MergeFingerprint;
import com.dataporter.migration.domain.merge.MergePreflightResult;
import com.dataporter.migration.ports.out.MigrationTarget;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.TargetConnectionException;
import com.dataporter.shared.error.TargetPreparationException;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.BulkWriteOptions;
import org.bson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class MongoMigrationTarget extends AbstractMongoReader implements MigrationTarget {
    private static final Logger log = LoggerFactory.getLogger(MongoMigrationTarget.class);
    public MongoMigrationTarget(Endpoint endpoint) { super(endpoint); }

    @Override public void checkConnection() {
        try { super.checkConnection(); }
        catch (RuntimeException e) { throw new TargetConnectionException("Cannot connect to target " + endpoint.safeUri(), e); }
    }

    @Override public MigrationPlan inspect() { return inspectPlan(); }

    public boolean hasUserObjects() {
        for (String name : database.listCollectionNames()) if (!name.startsWith("system.")) return true;
        return false;
    }

    public void dropDatabase() {
        log.warn("Dropping target database cluster={} database={} strategy=DROP_AND_RECREATE destructive=true",
                endpoint.safeUri(), endpoint.database());
        database.drop();
    }

    public void createCollection(CollectionDefinition definition) {
        try {
            BsonDocument options = MongoBson.decode(definition.options()).clone();
            BsonDocument command = new BsonDocument("create", new BsonString(definition.name()));
            options.forEach(command::put);
            run(command);
        } catch (RuntimeException e) {
            throw new MetadataMigrationException("Failed to create collection " + definition.name(), e);
        }
    }

    public void writeBatch(DataBatch batch) {
        if (batch.isEmpty()) return;
        try {
            List<RawBsonDocument> raw = batch.documents().stream().map(MongoBson::decode).toList();
            if (!database.getCollection(batch.collection(), RawBsonDocument.class).insertMany(raw).wasAcknowledged())
                throw new IllegalStateException("MIGRATE requires an acknowledged write concern");
        } catch (RuntimeException e) {
            throw new DocumentMigrationException("Batch write failed for " + batch.collection()
                    + MongoFailureDetails.classification(e)
                    + "; outcome may be partial and is not manually retried", e);
        }
    }

    public MergePreflightResult preflightMerge(MigrationPlan sourcePlan) {
        try {
            MigrationPlan targetPlan = inspect();
            Map<String, CollectionDefinition> targetCollections = new HashMap<>();
            targetPlan.collections().forEach(item -> targetCollections.put(item.name(), item));
            Map<String, ViewDefinition> targetViews = new HashMap<>();
            targetPlan.views().forEach(item -> targetViews.put(item.name(), item));
            Map<String, IndexDefinition> targetIndexes = new HashMap<>();
            targetPlan.indexes().forEach(item -> targetIndexes.put(indexKey(item), item));

            List<String> collectionsToCreate = new ArrayList<>();
            List<IndexDefinition> indexesToCreate = new ArrayList<>();
            List<String> viewsToCreate = new ArrayList<>();
            Map<String, Long> initialCounts = new LinkedHashMap<>();

            for (CollectionDefinition sourceCollection : sourcePlan.collections()) {
                if (targetViews.containsKey(sourceCollection.name()))
                    throw conflict("Target name is occupied by a view", sourceCollection.name());
                CollectionDefinition existing = targetCollections.get(sourceCollection.name());
                if (existing == null) {
                    collectionsToCreate.add(sourceCollection.name());
                    initialCounts.put(sourceCollection.name(), 0L);
                } else {
                    if (!MongoMetadata.collectionEquivalent(sourceCollection.options(), existing.options()))
                        throw conflict("Incompatible collection options", sourceCollection.name());
                    initialCounts.put(sourceCollection.name(), count(sourceCollection.name()));
                }
            }

            for (IndexDefinition sourceIndex : sourcePlan.indexes()) {
                IndexDefinition sameName = targetIndexes.get(indexKey(sourceIndex));
                if (sameName != null) {
                    if (!MongoMetadata.indexEquivalent(sourceIndex.specification(), sameName.specification()))
                        throw conflict("Incompatible index specification", sourceIndex.collection() + "." + sourceIndex.name());
                    continue;
                }
                for (IndexDefinition targetIndex : targetPlan.indexes()) {
                    if (sourceIndex.collection().equals(targetIndex.collection())
                            && MongoMetadata.sameIndexKey(sourceIndex.specification(), targetIndex.specification())) {
                        throw conflict("Equivalent index key already exists under target name " + targetIndex.name(),
                                sourceIndex.collection() + "." + sourceIndex.name());
                    }
                }
                indexesToCreate.add(sourceIndex);
            }

            for (ViewDefinition sourceView : sourcePlan.views()) {
                if (targetCollections.containsKey(sourceView.name()))
                    throw conflict("Target name is occupied by a collection", sourceView.name());
                ViewDefinition existing = targetViews.get(sourceView.name());
                if (existing == null) viewsToCreate.add(sourceView.name());
                else if (!sourceView.viewOn().equals(existing.viewOn())
                        || !MongoMetadata.viewEquivalent(sourceView.options(), existing.options()))
                    throw conflict("Incompatible view definition", sourceView.name());
            }
            return new MergePreflightResult(collectionsToCreate, indexesToCreate, viewsToCreate, initialCounts);
        } catch (TargetPreparationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TargetPreparationException("Cannot complete MERGE target preflight"
                    + MongoFailureDetails.classification(e), e);
        }
    }

    public MergeBatchResult mergeBatch(DataBatch batch) {
        if (batch.isEmpty()) return new MergeBatchResult(0, 0, MergeFingerprint.batch(List.of()));
        try {
            BulkWriteResult result = database.getCollection(batch.collection(), RawBsonDocument.class)
                    .bulkWrite(MongoWriteModels.replaceUpsertModels(batch), new BulkWriteOptions().ordered(false));
            if (!result.wasAcknowledged())
                throw new IllegalStateException("MERGE requires an acknowledged write concern");
            long inserted = result.getUpserts().size();
            long replaced = result.getMatchedCount();
            return new MergeBatchResult(inserted, replaced, MergeFingerprint.batch(batch.documents()));
        } catch (RuntimeException e) {
            throw new DocumentMigrationException("MERGE write failed for " + batch.collection()
                    + MongoFailureDetails.classification(e)
                    + "; outcome may be partial and is not manually retried", e);
        }
    }

    public void createIndex(IndexDefinition definition) {
        try {
            BsonDocument index = MongoBson.decode(definition.specification()).clone();
            BsonDocument command = new BsonDocument("createIndexes", new BsonString(definition.collection()))
                    .append("indexes", new BsonArray(List.of(index)));
            run(command);
        } catch (RuntimeException e) {
            throw new MetadataMigrationException("Failed to create index " + definition.name()
                    + " on " + definition.collection() + MongoFailureDetails.classification(e), e);
        }
    }

    public void createView(ViewDefinition definition) {
        try {
            BsonDocument options = MongoBson.decodeMutable(definition.options());
            BsonValue pipeline = options.remove("pipeline");
            options.remove("viewOn");
            BsonDocument command = new BsonDocument("create", new BsonString(definition.name()))
                    .append("viewOn", new BsonString(definition.viewOn()))
                    .append("pipeline", pipeline == null ? new BsonArray() : pipeline);
            options.forEach(command::put);
            run(command);
        } catch (RuntimeException e) {
            throw new MetadataMigrationException("Failed to create view " + definition.name(), e);
        }
    }

    private static String indexKey(IndexDefinition index) { return index.collection() + "\0" + index.name(); }

    private static TargetPreparationException conflict(String reason, String object) {
        return new TargetPreparationException("MERGE preflight conflict for " + object + ": " + reason);
    }
}
