package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.GenerationSourceInspection;
import com.dataporter.generation.domain.TemplateQuery;
import com.dataporter.generation.ports.out.GenerationSource;
import com.dataporter.generation.ports.out.GenerationSchemaSource;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.OperationCancelledException;
import com.dataporter.shared.error.SourceConnectionException;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.ports.out.BatchCursor;
import com.dataporter.shared.ports.out.CancellationToken;

import com.mongodb.client.model.Filters;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class MongoGenerationSource extends AbstractMongoReader implements GenerationSource, GenerationSchemaSource {
    public MongoGenerationSource(Endpoint endpoint) { super(endpoint); }

    @Override public void checkConnection() {
        try { super.checkConnection(); }
        catch (RuntimeException e) { throw new SourceConnectionException("Cannot connect to source " + endpoint.safeUri(), e); }
    }

    @Override public GenerationSourceInspection inspect() {
        List<String> collections = new ArrayList<>();
        List<String> views = new ArrayList<>();
        try {
            for (BsonDocument info : database.listCollections(BsonDocument.class)) {
                String name = info.getString("name").getValue();
                if (name.startsWith("system.")) continue;
                if ("view".equals(info.getString("type", new BsonString("collection")).getValue())) views.add(name);
                else collections.add(name);
            }
        } catch (RuntimeException e) { throw new SourceInspectionException("Cannot inspect source database", e); }
        Collections.sort(collections);
        Collections.sort(views);
        return new GenerationSourceInspection(collections, views);
    }

    @Override public BatchCursor openTemplateBatches(String collection, TemplateQuery query, int batchSize) {
        try { return openBatches(collection, MongoTemplateQuery.toBson(query), batchSize); }
        catch (RuntimeException e) {
            throw new SourceInspectionException("Cannot open filtered template cursor for collection " + collection, e);
        }
    }

    @Override public FieldSchema inspectFields(String collection, Set<String> configuredPaths, int batchSize,
                                               CancellationToken cancellation) {
        try {
            BsonDocument info = database.listCollections(BsonDocument.class)
                    .filter(Filters.eq("name", collection)).first();
            if (info == null || "view".equals(info.getString("type", new BsonString("collection")).getValue()))
                throw new SourceInspectionException("Configured source collection is missing or is a view: " + collection);
            BsonDocument options = info.getDocument("options", new BsonDocument());
            MongoFieldSchema schema = new MongoFieldSchema(configuredPaths);
            schema.readValidator(options.getDocument("validator", new BsonDocument()));
            try (var cursor = database.getCollection(collection, RawBsonDocument.class).find()
                    .batchSize(batchSize).iterator()) {
                while (true) {
                    if (cancellation.isCancellationRequested()) throw new OperationCancelledException();
                    if (!cursor.hasNext()) break;
                    schema.observe(cursor.next());
                }
            }
            return schema.result();
        } catch (SourceInspectionException | OperationCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SourceInspectionException("Cannot inspect fields of source collection " + collection, e);
        }
    }
}
