package com.dataporter.adapters.mongo;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.error.TargetConnectionException;
import com.dataporter.shared.ports.out.BatchCursor;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.*;
import org.bson.conversions.Bson;

import java.util.*;

abstract class AbstractMongoReader {
    private static final long SOFT_BATCH_BYTES = 16L * 1024 * 1024;
    private static final long ID_LOOKUP_CHUNK_BYTES = 8L * 1024 * 1024;
    protected final MongoClient client;
    protected final MongoDatabase database;
    protected final Endpoint endpoint;

    protected AbstractMongoReader(Endpoint endpoint) {
        this.endpoint = endpoint;
        this.client = MongoClients.create(endpoint.uri());
        this.database = client.getDatabase(endpoint.database());
    }

    public void checkConnection() { database.runCommand(new BsonDocument("ping", new BsonInt32(1))); }

    public void checkReadable() {
        try { database.listCollectionNames().first(); }
        catch (RuntimeException e) { throw new SourceInspectionException("Cannot read source database", e); }
    }

    public void checkWritable() {
        String probe = "__dataportergen_probe_" + UUID.randomUUID().toString().replace("-", "");
        boolean acknowledged;
        try {
            database.createCollection(probe);
            acknowledged = database.getCollection(probe, BsonDocument.class)
                    .insertOne(new BsonDocument("_id", new BsonInt32(1))).wasAcknowledged();
        } catch (RuntimeException e) {
            throw new TargetConnectionException("Target database is not writable", e);
        } finally {
            try { database.getCollection(probe).drop(); } catch (RuntimeException ignored) { }
        }
        if (!acknowledged) throw new TargetConnectionException("Target requires an acknowledged write concern");
    }

    public java.util.Optional<java.util.Set<String>> clusterHosts() {
        try {
            java.util.Set<String> resolved = new java.util.HashSet<>();
            for (com.mongodb.connection.ServerDescription server : client.getClusterDescription().getServerDescriptions()) {
                String host = server.getAddress().getHost();
                int port = server.getAddress().getPort();
                for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host))
                    resolved.add(address.getHostAddress() + ":" + port);
            }
            return resolved.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(resolved);
        } catch (RuntimeException | java.net.UnknownHostException e) {
            return java.util.Optional.empty();
        }
    }

    public boolean databaseExists() {
        for (String name : client.listDatabaseNames()) if (name.equals(endpoint.database())) return true;
        return false;
    }

    public MigrationPlan inspectPlan() {
        List<CollectionDefinition> collections = new ArrayList<>();
        List<IndexDefinition> indexes = new ArrayList<>();
        List<ViewDefinition> views = new ArrayList<>();
        for (BsonDocument info : database.listCollections(BsonDocument.class)) {
            String name = info.getString("name").getValue();
            if (name.startsWith("system.")) continue;
            String type = info.getString("type", new BsonString("collection")).getValue();
            BsonDocument options = info.getDocument("options", new BsonDocument()).clone();
            if (type.equals("view")) {
                String viewOn = options.getString("viewOn").getValue();
                views.add(new ViewDefinition(name, viewOn, MongoBson.encode(options)));
            } else {
                collections.add(new CollectionDefinition(name, MongoBson.encode(options)));
                for (BsonDocument index : database.getCollection(name, BsonDocument.class).listIndexes(BsonDocument.class)) {
                    String indexName = index.getString("name").getValue();
                    if ("_id_".equals(indexName)) continue;
                    BsonDocument portable = index.clone();
                    portable.remove("v");
                    portable.remove("ns");
                    indexes.add(new IndexDefinition(name, indexName, MongoBson.encode(portable)));
                }
            }
        }
        collections.sort(Comparator.comparing(CollectionDefinition::name));
        indexes.sort(Comparator.comparing(IndexDefinition::collection).thenComparing(IndexDefinition::name));
        views.sort(Comparator.comparing(ViewDefinition::name));
        return new MigrationPlan(collections, indexes, views);
    }

    public BatchCursor openBatches(String collection, int batchSize) {
        return openBatches(collection, new BsonDocument(), batchSize);
    }

    protected BatchCursor openBatches(String collection, BsonDocument filter, int batchSize) {
        MongoCursor<RawBsonDocument> cursor = database.getCollection(collection, RawBsonDocument.class)
                .find(filter).sort(Sorts.ascending("_id")).batchSize(batchSize).iterator();
        return new BatchCursor() {
            public DataBatch next() {
                if (!cursor.hasNext()) return null;
                List<BsonPayload> docs = new ArrayList<>(batchSize);
                long bytes = 0;
                while (docs.size() < batchSize && cursor.hasNext()) {
                    BsonPayload payload = MongoBson.encode(cursor.next());
                    docs.add(payload);
                    bytes += payload.size();
                    if (bytes >= SOFT_BATCH_BYTES) break;
                }
                return new DataBatch(collection, docs, bytes);
            }
            public void close() { cursor.close(); }
        };
    }

    public long count(String collection) { return database.getCollection(collection).countDocuments(); }

    public List<BsonPayload> findManyBySourceDocuments(String collection, List<BsonPayload> sourceDocuments) {
        if (sourceDocuments.isEmpty()) return List.of();
        List<BsonPayload> found = new ArrayList<>(sourceDocuments.size());
        List<BsonValue> ids = new ArrayList<>(sourceDocuments.size());
        long bytes = 0;
        for (BsonPayload payload : sourceDocuments) {
            BsonValue id = MongoBson.decode(payload).get("_id");
            if (id == null) throw new IllegalArgumentException("Source BSON document has no _id");
            ids.add(id);
            bytes += payload.size();
            if (bytes >= ID_LOOKUP_CHUNK_BYTES) {
                found.addAll(findByIds(collection, ids));
                ids.clear();
                bytes = 0;
            }
        }
        if (!ids.isEmpty()) found.addAll(findByIds(collection, ids));
        return found;
    }

    private List<BsonPayload> findByIds(String collection, List<BsonValue> ids) {
        List<BsonPayload> documents = new ArrayList<>(ids.size());
        try (MongoCursor<RawBsonDocument> cursor = database.getCollection(collection, RawBsonDocument.class)
                .find(Filters.in("_id", ids)).iterator()) {
            while (cursor.hasNext()) documents.add(MongoBson.encode(cursor.next()));
        }
        return documents;
    }

    public void close() { client.close(); }

    protected void run(Bson command) { database.runCommand(command, BsonDocument.class); }
}
