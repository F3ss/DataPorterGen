package com.dataporter.integration;

import com.dataporter.adapters.mongo.MongoMigrationSource;
import com.dataporter.adapters.mongo.MongoMigrationTarget;
import com.dataporter.adapters.mongo.MongoMigrationVerifier;
import com.dataporter.adapters.reporting.JsonReportWriter;
import com.dataporter.migration.application.MigrationService;
import com.dataporter.migration.domain.CollectionSelection;
import com.dataporter.migration.domain.ConsistencyMode;
import com.dataporter.migration.domain.ExistingTargetStrategy;
import com.dataporter.migration.domain.MigrationCommand;
import com.dataporter.migration.domain.MigrationOptions;
import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.migration.domain.ObjectResult;
import com.dataporter.migration.domain.RetrySettings;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.migration.ports.out.MigrationProgressReporter;
import com.dataporter.shared.domain.DatabaseObjectType;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.domain.OperationStatus;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.*;
import org.bson.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MongoMigrationIntegrationTest {
    @Container static final MongoDBContainer SOURCE = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));
    @Container static final MongoDBContainer TARGET = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    @Test
    void migratesBsonMetadataIndexesEmptyCollectionAndViewWithRenamedDatabase(@TempDir Path temp) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String sourceDb = "source_" + suffix;
        String targetDb = "target_" + suffix;
        seedSource(SOURCE.getReplicaSetUrl(), sourceDb);
        Endpoint sourceEndpoint = new Endpoint(SOURCE.getReplicaSetUrl(), sourceDb);
        Endpoint targetEndpoint = new Endpoint(TARGET.getReplicaSetUrl(), targetDb);
        MongoMigrationSource source = new MongoMigrationSource(sourceEndpoint);
        MongoMigrationTarget target = new MongoMigrationTarget(targetEndpoint);
        var command = new MigrationCommand(sourceEndpoint, targetEndpoint,
                new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, 2, 2,
                        true, VerificationLevel.FULL, false, new RetrySettings(2, 0, 0)));
        var service = new MigrationService(source, target, new MongoMigrationVerifier(source, target, 2),
                new JsonReportWriter(temp.resolve("report.json")), MigrationProgressReporter.noop(), () -> false);

        MigrationReport report = service.migrate(command);

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.verification().differences()).isEmpty();
        assertThat(report.objects()).extracting(ObjectResult::name)
                .contains("customers", "events", "empty_collection");
        try (MongoClient client = MongoClients.create(TARGET.getReplicaSetUrl())) {
            MongoDatabase db = client.getDatabase(targetDb);
            assertThat(db.listCollectionNames()).contains("customers", "events", "empty_collection", "active_customers_view");
            assertThat(db.getCollection("empty_collection").countDocuments()).isZero();
            assertThat(db.getCollection("customers").countDocuments()).isEqualTo(2);
            assertThat(db.getCollection("customers").listIndexes())
                    .extracting(index -> index.getString("name"))
                    .containsExactlyInAnyOrder("_id_", "email_unique", "active_created_compound",
                            "created_ttl", "name_sparse", "account_number_hashed");
            assertThat(client.listDatabaseNames()).doesNotContain(sourceDb);
        }
    }

    @Test
    void rejectsSameDatabaseThroughDifferentUriHostAliases(@TempDir Path temp) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String uri = SOURCE.getReplicaSetUrl();
        String database = "samedb_" + suffix;
        Endpoint sourceEndpoint = new Endpoint(uri, database);
        Endpoint targetEndpoint = new Endpoint(uri.replace("localhost", "127.0.0.1"), database);
        seedSource(sourceEndpoint.uri(), database);

        MigrationReport report = migrate(sourceEndpoint, targetEndpoint, CollectionSelection.all(),
                temp.resolve("same-db-alias.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors()).anySatisfy(issue -> assertThat(issue.message()).contains("same cluster"));
        try (MongoClient client = MongoClients.create(uri)) {
            assertThat(client.getDatabase(database).listCollectionNames())
                    .containsExactlyInAnyOrder("customers", "events", "empty_collection", "active_customers_view");
        }
    }

    @Test
    void rejectsUnacknowledgedTargetWriteConcernBeforeConnecting(@TempDir Path temp) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String targetUrl = TARGET.getReplicaSetUrl();
        Endpoint sourceEndpoint = new Endpoint(SOURCE.getReplicaSetUrl(), "source_" + suffix);
        Endpoint targetEndpoint = new Endpoint(targetUrl + (targetUrl.contains("?") ? "&w=0" : "?w=0"), "w0_" + suffix);
        seedSource(sourceEndpoint.uri(), sourceEndpoint.database());

        MigrationReport report = migrate(sourceEndpoint, targetEndpoint, CollectionSelection.all(),
                temp.resolve("w0-report.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.errors()).anySatisfy(issue -> assertThat(issue.message()).contains("acknowledged"));
        try (MongoClient client = MongoClients.create(TARGET.getReplicaSetUrl())) {
            assertThat(client.getDatabase(targetEndpoint.database()).listCollectionNames()).isEmpty();
        }
    }

    @Test
    void failIfExistsStopsBeforeCopying() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint sourceEndpoint = new Endpoint(SOURCE.getReplicaSetUrl(), "source_" + suffix);
        Endpoint targetEndpoint = new Endpoint(TARGET.getReplicaSetUrl(), "target_" + suffix);
        seedSource(sourceEndpoint.uri(), sourceEndpoint.database());
        try (MongoClient client = MongoClients.create(targetEndpoint.uri())) {
            client.getDatabase(targetEndpoint.database()).createCollection("existing");
        }
        MongoMigrationSource source = new MongoMigrationSource(sourceEndpoint);
        MongoMigrationTarget target = new MongoMigrationTarget(targetEndpoint);
        var command = new MigrationCommand(sourceEndpoint, targetEndpoint,
                new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, 10, 1,
                        false, VerificationLevel.METADATA_AND_COUNTS, false,
                        new RetrySettings(1, 0, 0)));

        MigrationReport report = new MigrationService(source, target,
                new MongoMigrationVerifier(source, target, 10), ignored -> {}, MigrationProgressReporter.noop(), () -> false).migrate(command);

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        try (MongoClient client = MongoClients.create(targetEndpoint.uri())) {
            assertThat(client.getDatabase(targetEndpoint.database()).listCollectionNames()).containsExactly("existing");
        }
    }

    @Test
    void includeCollectionsCopiesOnlySelectionIndexesAndDependentViews(@TempDir Path temp) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint sourceEndpoint = new Endpoint(SOURCE.getReplicaSetUrl(), "source_" + suffix);
        Endpoint targetEndpoint = new Endpoint(TARGET.getReplicaSetUrl(), "target_" + suffix);
        seedSource(sourceEndpoint.uri(), sourceEndpoint.database());

        MigrationReport report = migrate(sourceEndpoint, targetEndpoint,
                CollectionSelection.from(List.of("customers"), List.of()), temp.resolve("include-report.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collectionSelection().includeCollections()).containsExactly("customers");
        assertThat(report.objects()).filteredOn(item -> item.status() == ObjectStatus.SKIPPED)
                .extracting(ObjectResult::name).contains("events", "empty_collection");
        try (MongoClient client = MongoClients.create(targetEndpoint.uri())) {
            MongoDatabase target = client.getDatabase(targetEndpoint.database());
            assertThat(target.listCollectionNames()).containsExactlyInAnyOrder("customers", "active_customers_view");
            assertThat(target.getCollection("customers").listIndexes()).hasSize(6);
        }
    }

    @Test
    void excludeCollectionsOmitsSelectionButKeepsIndependentObjects(@TempDir Path temp) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint sourceEndpoint = new Endpoint(SOURCE.getReplicaSetUrl(), "source_" + suffix);
        Endpoint targetEndpoint = new Endpoint(TARGET.getReplicaSetUrl(), "target_" + suffix);
        seedSource(sourceEndpoint.uri(), sourceEndpoint.database());

        MigrationReport report = migrate(sourceEndpoint, targetEndpoint,
                CollectionSelection.from(List.of(), List.of("events")), temp.resolve("exclude-report.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collectionSelection().excludeCollections()).containsExactly("events");
        try (MongoClient client = MongoClients.create(targetEndpoint.uri())) {
            assertThat(client.getDatabase(targetEndpoint.database()).listCollectionNames())
                    .containsExactlyInAnyOrder("customers", "empty_collection", "active_customers_view");
        }
    }

    @Test
    void mergeOverwritesConflictingTargetDocumentAndRetainsTargetOnlyObjects(@TempDir Path temp) {
        Endpoint[] endpoints = mergeEndpoints();
        seedSource(endpoints[0].uri(), endpoints[0].database());
        BsonValue conflictId = seedCompatibleMergeTarget(endpoints[0], endpoints[1]);

        MigrationReport report = merge(endpoints[0], endpoints[1], VerificationLevel.FULL);

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.verification().successful()).isTrue();
        assertThat(report.safeToRetry()).isFalse();
        assertThat(report.objects()).filteredOn(item -> item.name().equals("customers")
                        && item.type() == DatabaseObjectType.COLLECTION).singleElement()
                .satisfies(item -> {
                    assertThat(item.sourceDocuments()).isEqualTo(2);
                    assertThat(item.insertedDocuments()).isEqualTo(1);
                    assertThat(item.replacedDocuments()).isEqualTo(1);
                    assertThat(item.conflicts()).isEqualTo(1);
                });
        try (MongoClient sourceClient = MongoClients.create(endpoints[0].uri());
             MongoClient client = MongoClients.create(endpoints[1].uri())) {
            MongoDatabase target = client.getDatabase(endpoints[1].database());
            assertThat(target.getCollection("customers").countDocuments()).isEqualTo(3);
            RawBsonDocument sourceDocument = sourceClient.getDatabase(endpoints[0].database())
                    .getCollection("customers", RawBsonDocument.class).find(Filters.eq("_id", conflictId)).first();
            RawBsonDocument targetDocument = target.getCollection("customers", RawBsonDocument.class)
                    .find(Filters.eq("_id", conflictId)).first();
            assertThat(rawBytes(targetDocument)).isEqualTo(rawBytes(sourceDocument));
            assertThat(targetDocument.getString("email").getValue()).isEqualTo("alice@example.test");
            assertThat(target.listCollectionNames()).contains("target_only_collection", "target_only_view",
                    "active_customers_view");
            assertThat(target.getCollection("customers").listIndexes())
                    .extracting(index -> index.getString("name"))
                    .contains("email_unique", "target_only_idx", "active_created_compound",
                            "created_ttl", "name_sparse", "account_number_hashed");
        }
    }

    @Test
    void mergeConflictFreeTargetPassesFullVerificationWithExtraTargetDocuments(@TempDir Path temp) {
        Endpoint[] endpoints = mergeEndpoints();
        seedSource(endpoints[0].uri(), endpoints[0].database());
        createCompatibleCustomers(endpoints[1]);
        try (MongoClient client = MongoClients.create(endpoints[1].uri())) {
            MongoDatabase target = client.getDatabase(endpoints[1].database());
            target.getCollection("customers", BsonDocument.class).insertOne(
                    new BsonDocument("_id", new BsonObjectId(new ObjectId()))
                            .append("email", new BsonString("target-only@example.test"))
                            .append("active", BsonBoolean.FALSE).append("targetOnly", BsonBoolean.TRUE));
        }

        MigrationReport report = merge(endpoints[0], endpoints[1], VerificationLevel.FULL);

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.verification().differences()).isEmpty();
        assertThat(report.objects()).filteredOn(item -> item.name().equals("customers")
                        && item.type() == DatabaseObjectType.COLLECTION).singleElement()
                .satisfies(item -> {
                    assertThat(item.insertedDocuments()).isEqualTo(2);
                    assertThat(item.replacedDocuments()).isZero();
                    assertThat(item.conflicts()).isZero();
                });
        try (MongoClient client = MongoClients.create(endpoints[1].uri())) {
            assertThat(client.getDatabase(endpoints[1].database()).getCollection("customers").countDocuments())
                    .isEqualTo(3);
        }
    }

    @Test
    void mergePreservesRawSourceBsonAndSupportsMetadataCountVerification(@TempDir Path temp) {
        Endpoint[] endpoints = mergeEndpoints();
        seedSource(endpoints[0].uri(), endpoints[0].database());
        BsonValue conflictId = seedCompatibleMergeTarget(endpoints[0], endpoints[1]);

        MigrationReport report = merge(endpoints[0], endpoints[1], VerificationLevel.METADATA_AND_COUNTS);

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.verification().successful()).isTrue();
        assertThat(report.objects()).filteredOn(item -> item.name().equals("customers")
                        && item.type() == DatabaseObjectType.COLLECTION).singleElement()
                .satisfies(item -> {
                    assertThat(item.insertedDocuments()).isEqualTo(1);
                    assertThat(item.replacedDocuments()).isEqualTo(1);
                });
        try (MongoClient sourceClient = MongoClients.create(endpoints[0].uri());
             MongoClient targetClient = MongoClients.create(endpoints[1].uri())) {
            RawBsonDocument sourceDocument = sourceClient.getDatabase(endpoints[0].database())
                    .getCollection("customers", RawBsonDocument.class).find(Filters.eq("_id", conflictId)).first();
            RawBsonDocument targetDocument = targetClient.getDatabase(endpoints[1].database())
                    .getCollection("customers", RawBsonDocument.class).find(Filters.eq("_id", conflictId)).first();
            assertThat(rawBytes(targetDocument)).isEqualTo(rawBytes(sourceDocument));
            assertThat(targetClient.getDatabase(endpoints[1].database()).listCollectionNames())
                    .contains("target_only_collection", "target_only_view");
        }
    }

    @Test
    void mergeRejectsCollectionIndexAndViewConflictsBeforeDocuments(@TempDir Path temp) {
        Endpoint[] collectionConflict = mergeEndpoints();
        seedSource(collectionConflict[0].uri(), collectionConflict[0].database());
        try (MongoClient client = MongoClients.create(collectionConflict[1].uri())) {
            client.getDatabase(collectionConflict[1].database()).createCollection("customers");
        }
        assertPreflightFailureWithoutDocuments(collectionConflict,
                "collection options");

        Endpoint[] indexConflict = mergeEndpoints();
        seedSource(indexConflict[0].uri(), indexConflict[0].database());
        createCompatibleCustomers(indexConflict[1]);
        try (MongoClient client = MongoClients.create(indexConflict[1].uri())) {
            client.getDatabase(indexConflict[1].database()).getCollection("customers")
                    .createIndex(Indexes.ascending("different"), new IndexOptions().name("email_unique").unique(true));
        }
        assertPreflightFailureWithoutDocuments(indexConflict,
                "index specification");

        Endpoint[] equivalentKeyConflict = mergeEndpoints();
        seedSource(equivalentKeyConflict[0].uri(), equivalentKeyConflict[0].database());
        createCompatibleCustomers(equivalentKeyConflict[1]);
        try (MongoClient client = MongoClients.create(equivalentKeyConflict[1].uri())) {
            client.getDatabase(equivalentKeyConflict[1].database()).getCollection("customers")
                    .createIndex(Indexes.ascending("email"), new IndexOptions().name("different_email_name").unique(true));
        }
        assertPreflightFailureWithoutDocuments(equivalentKeyConflict,
                "equivalent index key");

        Endpoint[] viewConflict = mergeEndpoints();
        seedSource(viewConflict[0].uri(), viewConflict[0].database());
        createCompatibleCustomers(viewConflict[1]);
        try (MongoClient client = MongoClients.create(viewConflict[1].uri())) {
            client.getDatabase(viewConflict[1].database()).createView("active_customers_view", "customers",
                    List.of(Aggregates.match(Filters.eq("active", false))));
        }
        assertPreflightFailureWithoutDocuments(viewConflict,
                "view definition");
    }

    @Test
    void mergeDoesNotMaskTargetOnlyUniqueIndexAsIdConflict(@TempDir Path temp) {
        Endpoint[] endpoints = mergeEndpoints();
        seedSource(endpoints[0].uri(), endpoints[0].database());
        createCompatibleCustomers(endpoints[1]);
        try (MongoClient sourceClient = MongoClients.create(endpoints[0].uri());
             MongoClient targetClient = MongoClients.create(endpoints[1].uri())) {
            Document sourceFirst = sourceClient.getDatabase(endpoints[0].database()).getCollection("customers")
                    .find().sort(Sorts.ascending("_id")).first();
            targetClient.getDatabase(endpoints[1].database()).getCollection("customers").insertMany(List.of(
                    new Document("_id", sourceFirst.get("_id")).append("email", "target-wins@example.test")
                            .append("active", true),
                    new Document("_id", new ObjectId()).append("email", "target-only@example.test")
                            .append("active", false)));
            targetClient.getDatabase(endpoints[1].database()).getCollection("customers")
                    .createIndex(Indexes.ascending("active"), new IndexOptions().name("target_active_unique").unique(true));
        }

        MigrationReport report = merge(endpoints[0], endpoints[1], VerificationLevel.METADATA_AND_COUNTS);

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.safeToRetry()).isFalse();
        assertThat(report.errors()).anySatisfy(issue -> {
            assertThat(issue.message()).contains("MongoDB code=11000", "index=target_active_unique");
            assertThat(issue.message()).doesNotContain("dup key", "target-wins@example.test");
        });
    }

    private static void assertPreflightFailureWithoutDocuments(Endpoint[] endpoints, String expectedMessage) {
        MigrationReport report = merge(endpoints[0], endpoints[1], VerificationLevel.FULL);
        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.safeToRetry()).isTrue();
        assertThat(report.errors()).anySatisfy(issue -> assertThat(issue.message()).containsIgnoringCase(expectedMessage));
        try (MongoClient client = MongoClients.create(endpoints[1].uri())) {
            assertThat(client.getDatabase(endpoints[1].database()).getCollection("customers").countDocuments()).isZero();
        }
    }

    private static Endpoint[] mergeEndpoints() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new Endpoint[] {
                new Endpoint(SOURCE.getReplicaSetUrl(), "merge_source_" + suffix),
                new Endpoint(TARGET.getReplicaSetUrl(), "merge_target_" + suffix)
        };
    }

    private static MigrationReport merge(Endpoint sourceEndpoint, Endpoint targetEndpoint,
                                         VerificationLevel verificationLevel) {
        MongoMigrationSource source = new MongoMigrationSource(sourceEndpoint);
        MongoMigrationTarget target = new MongoMigrationTarget(targetEndpoint);
        var command = new MigrationCommand(sourceEndpoint, targetEndpoint,
                new MigrationOptions(ExistingTargetStrategy.MERGE, ConsistencyMode.BASIC, 2, 2,
                        true, verificationLevel,
                        CollectionSelection.from(List.of("customers"), List.of()), false,
                        new RetrySettings(1, 0, 0)));
        return new MigrationService(source, target, new MongoMigrationVerifier(source, target, 2),
                ignored -> {}, MigrationProgressReporter.noop(), () -> false).migrate(command);
    }

    private static BsonValue seedCompatibleMergeTarget(Endpoint sourceEndpoint, Endpoint targetEndpoint) {
        createCompatibleCustomers(targetEndpoint);
        try (MongoClient sourceClient = MongoClients.create(sourceEndpoint.uri());
             MongoClient targetClient = MongoClients.create(targetEndpoint.uri())) {
            MongoDatabase source = sourceClient.getDatabase(sourceEndpoint.database());
            MongoDatabase target = targetClient.getDatabase(targetEndpoint.database());
            RawBsonDocument sourceFirst = source.getCollection("customers", RawBsonDocument.class)
                    .find().sort(Sorts.ascending("_id")).first();
            BsonValue conflictId = sourceFirst.get("_id");
            target.getCollection("customers", BsonDocument.class).insertMany(List.of(
                    new BsonDocument("_id", conflictId).append("email", new BsonString("target-wins@example.test"))
                            .append("active", BsonBoolean.TRUE).append("targetMarker", BsonBoolean.TRUE),
                    new BsonDocument("_id", new BsonObjectId(new ObjectId()))
                            .append("email", new BsonString("target-only@example.test"))
                            .append("active", BsonBoolean.FALSE).append("targetOnly", BsonBoolean.TRUE)));
            target.getCollection("customers").createIndex(Indexes.ascending("email"),
                    new IndexOptions().unique(true).name("email_unique"));
            target.getCollection("customers").createIndex(Indexes.ascending("targetOnly"),
                    new IndexOptions().name("target_only_idx"));
            target.createCollection("target_only_collection");
            target.createView("target_only_view", "target_only_collection", List.of());
            target.createView("active_customers_view", "customers",
                    List.of(Aggregates.match(Filters.eq("active", true))));
            return conflictId;
        }
    }

    private static void createCompatibleCustomers(Endpoint targetEndpoint) {
        try (MongoClient client = MongoClients.create(targetEndpoint.uri())) {
            BsonDocument validator = BsonDocument.parse("{ $jsonSchema: { bsonType: 'object', required: ['email'] } }");
            client.getDatabase(targetEndpoint.database()).runCommand(
                    new BsonDocument("create", new BsonString("customers")).append("validator", validator));
        }
    }

    private static byte[] rawBytes(RawBsonDocument document) {
        return Arrays.copyOfRange(document.getBackingArray(), document.getByteOffset(),
                document.getByteOffset() + document.getByteLength());
    }

    private static MigrationReport migrate(Endpoint sourceEndpoint, Endpoint targetEndpoint,
                                           CollectionSelection selection, Path reportPath) {
        MongoMigrationSource source = new MongoMigrationSource(sourceEndpoint);
        MongoMigrationTarget target = new MongoMigrationTarget(targetEndpoint);
        var command = new MigrationCommand(sourceEndpoint, targetEndpoint,
                new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, 2, 2,
                        true, VerificationLevel.FULL, selection, false,
                        new RetrySettings(2, 0, 0)));
        return new MigrationService(source, target, new MongoMigrationVerifier(source, target, 2),
                new JsonReportWriter(reportPath), MigrationProgressReporter.noop(), () -> false).migrate(command);
    }

    private static void seedSource(String uri, String databaseName) {
        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(databaseName);
            BsonDocument validator = BsonDocument.parse("{ $jsonSchema: { bsonType: 'object', required: ['email'] } }");
            db.runCommand(new BsonDocument("create", new BsonString("customers")).append("validator", validator));
            db.createCollection("events");
            db.createCollection("empty_collection");
            MongoCollection<Document> customers = db.getCollection("customers");
            customers.insertMany(List.of(
                    new Document("_id", new ObjectId()).append("email", "alice@example.test").append("active", true)
                            .append("name", "Алиса 東京").append("balance", new Decimal128(new java.math.BigDecimal("1234.50")))
                            .append("createdAt", Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                            .append("nested", new Document("count", 7L)).append("tags", List.of("one", "два")),
                    new Document("_id", new ObjectId()).append("email", "bob@example.test").append("active", false)
                            .append("nullable", null).append("score", 1.25d).append("small", 3)));
            db.getCollection("events").insertOne(new Document("_id", new ObjectId())
                    .append("timestamp", new BsonTimestamp(42, 1))
                    .append("binary", new Binary((byte) 0x00, ByteBuffer.allocate(8).putLong(99).array())));
            customers.createIndex(Indexes.ascending("email"), new IndexOptions().unique(true).name("email_unique"));
            customers.createIndex(Indexes.compoundIndex(Indexes.ascending("active"), Indexes.descending("createdAt")),
                    new IndexOptions().name("active_created_compound"));
            customers.createIndex(Indexes.ascending("createdAt"), new IndexOptions().expireAfter(30L, TimeUnit.DAYS).name("created_ttl"));
            customers.createIndex(Indexes.ascending("name"), new IndexOptions().sparse(true).name("name_sparse"));
            customers.createIndex(Indexes.hashed("accountNumber"), new IndexOptions().name("account_number_hashed"));
            db.createView("active_customers_view", "customers", List.of(Aggregates.match(Filters.eq("active", true))));
        }
    }
}
