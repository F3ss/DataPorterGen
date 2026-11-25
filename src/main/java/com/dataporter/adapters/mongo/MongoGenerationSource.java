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
import com.dataporter.shared.security.SecretMasker;

import com.mongodb.client.model.Filters;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class MongoGenerationSource extends AbstractMongoReader implements GenerationSource, GenerationSchemaSource {
    private static final Logger log = LoggerFactory.getLogger(MongoGenerationSource.class);
    private static final long SCHEMA_PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

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

    @Override public FieldSchema inspectFields(String collection, Set<String> configuredPaths, int batchSize, int maxDocuments,
                                               CancellationToken cancellation) {
        String safeCollection = SecretMasker.redact(collection);
        long started = System.nanoTime();
        log.info("Starting source schema inspection collection={}", safeCollection);
        try {
            BsonDocument info = database.listCollections(BsonDocument.class)
                    .filter(Filters.eq("name", collection)).first();
            if (info == null || "view".equals(info.getString("type", new BsonString("collection")).getValue()))
                throw new SourceInspectionException("Configured source collection is missing or is a view: " + collection);
            BsonDocument options = info.getDocument("options", new BsonDocument());
            MongoFieldSchema schema = new MongoFieldSchema(configuredPaths);
            schema.readValidator(options.getDocument("validator", new BsonDocument()));
            int documentsScanned = 0;
            long lastProgress = started;
            boolean truncated;
            // The extra document only distinguishes an exhausted collection from a truncated inspection.
            try (var cursor = database.getCollection(collection, RawBsonDocument.class).find()
                    .limit(maxDocuments + 1).batchSize(Math.min(batchSize, maxDocuments)).iterator()) {
                while (documentsScanned < maxDocuments) {
                    if (cancellation.isCancellationRequested()) throw new OperationCancelledException();
                    if (!cursor.hasNext()) break;
                    schema.observe(cursor.next());
                    documentsScanned++;
                    long now = System.nanoTime();
                    if (now - lastProgress >= SCHEMA_PROGRESS_INTERVAL_NANOS) {
                        log.info("Source schema inspection progress collection={} documentsScanned={} elapsedSeconds={}",
                                safeCollection, documentsScanned, TimeUnit.NANOSECONDS.toSeconds(now - started));
                        lastProgress = now;
                    }
                }
                if (cancellation.isCancellationRequested()) throw new OperationCancelledException();
                truncated = documentsScanned == maxDocuments && cursor.hasNext();
            }
            ScanCoverage coverage = truncated ? ScanCoverage.TRUNCATED
                    : documentsScanned == 0 ? ScanCoverage.EMPTY : ScanCoverage.COMPLETE;
            log.info("Source schema inspection completed collection={} documentsScanned={} truncated={} elapsedSeconds={}",
                    safeCollection, documentsScanned, truncated, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started));
            return schema.result(coverage);
        } catch (SourceInspectionException | OperationCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SourceInspectionException("Cannot inspect fields of source collection " + collection, e);
        }
    }
}
