package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.generation.domain.error.GenerationException;
import com.dataporter.generation.ports.out.GenerationTarget;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.TargetConnectionException;
import com.dataporter.shared.error.TargetPreparationException;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.*;
import org.bson.*;

import java.util.*;

public final class MongoGenerationTarget extends AbstractMongoReader implements GenerationTarget {
    public MongoGenerationTarget(Endpoint endpoint) { super(endpoint); }

    @Override public void checkConnection() {
        try { super.checkConnection(); }
        catch (RuntimeException e) { throw new TargetConnectionException("Cannot connect to target " + endpoint.safeUri(), e); }
    }

    @Override public void upsert(DataBatch batch) {
        if (batch.isEmpty()) return;
        try {
            BulkWriteResult result = database.getCollection(batch.collection(), RawBsonDocument.class)
                    .bulkWrite(MongoWriteModels.replaceUpsertModels(batch), new BulkWriteOptions().ordered(true));
            if (!result.wasAcknowledged())
                throw new IllegalStateException("GENERATE requires an acknowledged write concern");
        } catch (RuntimeException e) {
            throw new GenerationException("Generation upsert failed for " + batch.collection()
                    + MongoFailureDetails.classification(e)
                    + "; outcome may be partial and is not manually retried", e);
        }
    }

    @Override public Set<String> ordinaryCollections() {
        try {
            Set<String> names = new LinkedHashSet<>();
            for (BsonDocument info : database.listCollections(BsonDocument.class)) {
                String name = info.getString("name").getValue();
                String type = info.getString("type", new BsonString("collection")).getValue();
                if (!name.startsWith("system.") && type.equals("collection")) names.add(name);
            }
            return Set.copyOf(names);
        } catch (RuntimeException e) { throw new TargetPreparationException("Cannot inspect target collections", e); }
    }

    @Override public List<UniqueConstraint> uniqueConstraints(String collection) {
        try {
            List<UniqueConstraint> constraints = new ArrayList<>();
            constraints.add(new UniqueConstraint(collection, "_id_", List.of("/_id"), false, false, false));
            for (BsonDocument index : database.getCollection(collection, BsonDocument.class).listIndexes(BsonDocument.class)) {
                if ("_id_".equals(index.getString("name").getValue())) continue;
                if (!index.getBoolean("unique", BsonBoolean.FALSE).getValue()) continue;
                BsonDocument key = index.getDocument("key");
                List<String> paths = key.keySet().stream().map(MongoGenerationTarget::fieldPath).toList();
                BsonDocument collation = index.getDocument("collation", null);
                boolean nonSimple = collation != null && !"simple".equals(collation.getString("locale", new BsonString("simple")).getValue());
                constraints.add(new UniqueConstraint(collection, index.getString("name").getValue(), paths,
                        index.containsKey("partialFilterExpression"), index.getBoolean("sparse", BsonBoolean.FALSE).getValue(), nonSimple));
            }
            return List.copyOf(constraints);
        } catch (RuntimeException e) { throw new TargetPreparationException("Cannot inspect unique indexes for " + collection, e); }
    }

    @Override public long nextSequenceStart(String collection, String jsonPointer, long step) {
        if (step <= 0) throw new TargetPreparationException("AUTO_AFTER_TARGET_MAX requires a positive step");
        String field = Arrays.stream(jsonPointer.substring(1).split("/"))
                .map(token -> token.replace("~1", "/").replace("~0", "~")).reduce((a,b) -> a + "." + b).orElseThrow();
        try {
            BsonDocument found = database.getCollection(collection, BsonDocument.class)
                    .find(Filters.or(Filters.type(field, BsonType.INT32), Filters.type(field, BsonType.INT64)))
                    .sort(Sorts.descending(field)).projection(Projections.include(field)).first();
            if (found == null) return 1;
            BsonValue value = nested(found, field);
            long max = value.isInt64() ? value.asInt64().getValue() : value.asInt32().getValue();
            return Math.addExact(max, step);
        } catch (RuntimeException e) { throw new TargetPreparationException("Cannot resolve sequence start for " + collection + jsonPointer, e); }
    }

    @Override public boolean constraintKeyConflicts(UniqueConstraint constraint, List<BsonPayload> payloads,
                                                    List<BsonPayload> idPayloads) {
        if (payloads.isEmpty()) return false;
        try {
            List<org.bson.conversions.Bson> terms = new ArrayList<>(payloads.size());
            for (int i = 0; i < payloads.size(); i++) {
                BsonDocument key = MongoBson.decode(payloads.get(i));
                if (key.isEmpty()) continue;
                BsonDocument conditions = new BsonDocument();
                key.forEach((pointer, value) -> conditions.put(pointerToField(pointer), new BsonDocument("$eq", value)));
                BsonValue id = MongoBson.decode(idPayloads.get(i)).get("/_id");
                if (id == null) throw new IllegalArgumentException("missing generated _id key");
                conditions.put("_id", new BsonDocument("$ne", id));
                terms.add(conditions);
            }
            if (terms.isEmpty()) return false;
            org.bson.conversions.Bson filter = terms.size() == 1 ? terms.get(0) : Filters.or(terms);
            return database.getCollection(constraint.collection(), BsonDocument.class).find(filter).limit(1).first() != null;
        } catch (RuntimeException e) { throw new TargetPreparationException("Cannot check conflicting target key for " + constraint.name(), e); }
    }

    private static String fieldPath(String dotted) {
        return "/" + Arrays.stream(dotted.split("\\.")).map(v -> v.replace("~", "~0").replace("/", "~1"))
                .reduce((a,b) -> a + "/" + b).orElse("");
    }
    private static BsonValue nested(BsonDocument document, String dotted) {
        BsonValue value = document;
        for (String part : dotted.split("\\.")) value = value.asDocument().get(part);
        return value;
    }
    private static String pointerToField(String pointer) {
        return Arrays.stream(pointer.substring(1).split("/"))
                .map(token -> token.replace("~1", "/").replace("~0", "~"))
                .reduce((a,b) -> a + "." + b).orElseThrow();
    }
}
