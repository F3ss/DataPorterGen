package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.GenerationSourceInspection;
import com.dataporter.generation.domain.TemplateQuery;
import com.dataporter.generation.ports.out.GenerationSource;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.SourceConnectionException;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.ports.out.BatchCursor;

import org.bson.BsonDocument;
import org.bson.BsonString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MongoGenerationSource extends AbstractMongoReader implements GenerationSource {
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
}
