package com.dataporter.integration;

import com.dataporter.adapters.config.GenerationConfigReader;
import com.dataporter.adapters.cli.ExitCodes;
import com.dataporter.adapters.cli.MigrationCliRunner;
import com.dataporter.adapters.mongo.MongoGenerationBsonEngine;
import com.dataporter.adapters.mongo.MongoGenerationSource;
import com.dataporter.adapters.mongo.MongoGenerationTarget;
import com.dataporter.adapters.reporting.JsonReportWriter;
import com.dataporter.adapters.snapshot.FileTemplateCatalogFactory;
import com.dataporter.config.MigrationProperties;
import com.dataporter.generation.application.GenerationOrchestrator;
import com.dataporter.generation.domain.GenerationCommand;
import com.dataporter.generation.domain.GenerationOptions;
import com.dataporter.generation.domain.GenerationReport;
import com.dataporter.generation.domain.TemplateSelection;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.domain.OperationStatus;
import com.dataporter.shared.domain.OperationMode;

import com.mongodb.client.*;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MongoGenerationIntegrationTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    @Test void reconcilesYamlAgainstDeclaredAndObservedFieldsWithOnlySourceConfigured(@TempDir Path temp) throws Exception {
        var properties = new MigrationProperties();
        properties.setMode(OperationMode.VALIDATE_CONFIG);
        properties.getSource().setUri(MONGO.getReplicaSetUrl());
        properties.getSource().setDatabase("config_source_" + UUID.randomUUID().toString().replace("-", ""));
        try (MongoClient client = MongoClients.create(properties.getSource().getUri())) {
            MongoDatabase source = client.getDatabase(properties.getSource().getDatabase());
            source.createCollection("items", new CreateCollectionOptions().validationOptions(new ValidationOptions()
                    .validator(new Document("$jsonSchema", new Document("properties",
                            new Document("declared", new Document("bsonType", "string"))
                                    .append("hint", new Document("bsonType", "date")))))));
            source.getCollection("items").insertMany(List.of(
                    new Document("_id", 1).append("selected", true),
                    new Document("_id", 2).append("outsideQuery", 123L).append("note", "fixture-private-source-value")));
        }
        Path config = temp.resolve("generation.yml");
        properties.getGeneration().setConfigPath(config.toString());
        Files.writeString(config, """
                version: 1
                collections:
                  - name: items
                    count: 1
                    query: { selected: true }
                    fields:
                      /declared: { kind: literal, value: "a" }
                      /obsolete: { kind: literal, value: "b" }
                """);

        var runner = new MigrationCliRunner(properties);
        runner.run(new DefaultApplicationArguments());

        assertThat(runner.exitCode()).isEqualTo(ExitCodes.SUCCESS);
        assertThat(Files.readString(config))
                .contains("# /obsolete:", "# /hint: DATE_TIME  # example: {\"$date\":",
                        "# /outsideQuery: INT64  # example: {\"$numberLong\":", "# /note: STRING  # example: \"sample-")
                .doesNotContain("fixture-private-source-value");
        assertThat(new GenerationConfigReader(config).read().collections().getFirst().fields()).containsOnlyKeys("/declared");
    }

    @Test void limitsValidationPerCollectionAndPreservesRulesOnTruncatedScans(@TempDir Path temp) throws Exception {
        var properties = new MigrationProperties();
        properties.setMode(OperationMode.VALIDATE_CONFIG);
        properties.getSource().setUri(MONGO.getReplicaSetUrl());
        properties.getSource().setDatabase("sampling_source_" + UUID.randomUUID().toString().replace("-", ""));
        try (MongoClient client = MongoClients.create(properties.getSource().getUri())) {
            MongoDatabase source = client.getDatabase(properties.getSource().getDatabase());
            source.getCollection("large").insertMany(List.of(
                    new Document("_id", 1).append("sampled", true),
                    new Document("_id", 2).append("later", 123L).append("rare", "seen after the limit")));
            source.getCollection("small").insertOne(new Document("_id", 1).append("smallField", "yes"));
        }
        Path config = temp.resolve("generation.yml");
        properties.getGeneration().setConfigPath(config.toString());
        properties.getGeneration().setValidationMaxDocuments(1);
        Files.writeString(config, """
                version: 1
                batchSize: 1
                collections:
                  - name: large
                    count: 1
                    fields:
                      /rare: { kind: literal, value: "a" }
                      /obsolete: { kind: literal, value: "b" }
                  - name: small
                    count: 1
                    fields:
                      /old: { kind: literal, value: true }
                """);

        var runner = new MigrationCliRunner(properties);
        runner.run(new DefaultApplicationArguments());

        assertThat(runner.exitCode()).isEqualTo(ExitCodes.SUCCESS);
        assertThat(Files.readString(config)).contains("# /sampled: BOOLEAN", "# /smallField: STRING", "# /old:")
                .doesNotContain("# /later:", "# /obsolete:");
        var collections = new GenerationConfigReader(config).read().collections();
        assertThat(collections.getFirst().fields()).containsOnlyKeys("/rare", "/obsolete");
        assertThat(collections.getLast().fields()).isEmpty();

        properties.getGeneration().setValidationMaxDocuments(2);
        runner.run(new DefaultApplicationArguments());

        assertThat(runner.exitCode()).isEqualTo(ExitCodes.SUCCESS);
        assertThat(Files.readString(config)).contains("# /later: INT64", "# /obsolete:");
        assertThat(new GenerationConfigReader(config).read().collections().getFirst().fields()).containsOnlyKeys("/rare");
    }

    @Test void filtersTemplateSnapshotWithMongoQueryBeforeGeneration(@TempDir Path temp) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint source = new Endpoint(MONGO.getReplicaSetUrl(), "generation_query_source_" + suffix);
        Endpoint target = new Endpoint(MONGO.getReplicaSetUrl(), "generation_query_target_" + suffix);
        try (MongoClient client = MongoClients.create(source.uri())) {
            client.getDatabase(source.database()).getCollection("items").insertMany(List.of(
                    new Document("_id", new ObjectId("000000000000000000000001")).append("requiredValue", "A").append("amount", 1L),
                    new Document("_id", new ObjectId("000000000000000000000002")).append("amount", "excluded"),
                    new Document("_id", new ObjectId("000000000000000000000003")).append("requiredValue", "C").append("amount", 3L)));
            client.getDatabase(target.database()).getCollection("items")
                    .insertOne(new Document("_id", "target-template"));
        }
        Path config = temp.resolve("filtered-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                templateSelection: SEQUENTIAL
                batchSize: 2
                parallelism: 2
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 3
                    query: { "requiredValue": { "$exists": true } }
                    fields:
                      /_id: { kind: objectId }
                      /copied: { kind: ref, path: /requiredValue }
                      /ordinal: { kind: sequence, start: 0, step: 1 }
                      /amount: { kind: randomNumber, bsonType: INT32, min: 10, max: 10 }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport mismatch = run(source, target, config, false, temp.resolve("mismatch-report.json"));
        assertThat(mismatch.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(mismatch.errors()).singleElement().satisfies(issue ->
                assertThat(issue.message()).contains("items/amount", "INT32", "INT64").doesNotContain("STRING"));
        assertThat(Files.readString(config)).contains("bsonType: INT32");

        GenerationReport report = run(source, target, config, new GenerationOptions(false, false, true, true),
                temp.resolve("filtered-report.json"));

        assertThat(report.status()).as(report.errors().toString()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collections().getFirst().snapshotTemplates()).isEqualTo(2);
        assertThat(report.configHash()).isEqualTo(new GenerationConfigReader(config).read().configHash());
        try (MongoClient client = MongoClients.create(target.uri())) {
            List<Document> generated = client.getDatabase(target.database()).getCollection("items")
                    .find(new Document("generated", true)).sort(Sorts.ascending("ordinal")).into(new ArrayList<>());
            assertThat(generated).extracting(document -> document.getString("copied"))
                    .containsExactly("A", "C", "A");
            assertThat(generated).extracting(document -> document.getLong("amount")).containsOnly(10L);
        }
    }

    @Test void emptyTemplateQueryResultFailsBeforeTargetAccess(@TempDir Path temp) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint source = new Endpoint(MONGO.getReplicaSetUrl(), "generation_empty_query_source_" + suffix);
        Endpoint target = new Endpoint(MONGO.getReplicaSetUrl(), "generation_empty_query_target_" + suffix);
        try (MongoClient client = MongoClients.create(source.uri())) {
            client.getDatabase(source.database()).getCollection("items")
                    .insertOne(new Document("_id", 1).append("value", "template"));
            client.getDatabase(target.database()).getCollection("items")
                    .insertOne(new Document("_id", "untouched").append("marker", true));
        }
        Path config = temp.resolve("empty-filtered-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 1
                    query: { "missing": { "$exists": true } }
                    fields:
                      /_id: { kind: objectId }
                """);

        GenerationReport report = run(source, target, config, false, temp.resolve("empty-filter-report.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.safeToRetry()).isTrue();
        assertThat(report.errors()).singleElement().satisfies(issue ->
                assertThat(issue.stage()).isEqualTo("SNAPSHOT_TEMPLATES"));
        try (MongoClient client = MongoClients.create(target.uri())) {
            MongoCollection<Document> items = client.getDatabase(target.database()).getCollection("items");
            assertThat(items.countDocuments()).isEqualTo(1);
            assertThat(items.find(new Document("_id", "untouched")).first()).containsEntry("marker", true);
        }
    }

    @Test void invalidMongoTemplateQueryFailsWithoutDisclosingValuesOrTouchingTarget(@TempDir Path temp) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint source = new Endpoint(MONGO.getReplicaSetUrl(), "generation_invalid_query_source_" + suffix);
        Endpoint target = new Endpoint(MONGO.getReplicaSetUrl(), "generation_invalid_query_target_" + suffix);
        try (MongoClient client = MongoClients.create(source.uri())) {
            client.getDatabase(source.database()).getCollection("items")
                    .insertOne(new Document("_id", 1).append("value", "template"));
            client.getDatabase(target.database()).getCollection("items")
                    .insertOne(new Document("_id", "untouched").append("marker", true));
        }
        Path config = temp.resolve("invalid-filtered-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 1
                    query: { "$and": "sensitive-query-value" }
                    fields:
                      /_id: { kind: objectId }
                """);

        GenerationReport report = run(source, target, config, false, temp.resolve("invalid-filter-report.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(report.safeToRetry()).isTrue();
        assertThat(report.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.stage()).isEqualTo("SNAPSHOT_TEMPLATES");
            assertThat(issue.message()).doesNotContain("sensitive-query-value");
        });
        try (MongoClient client = MongoClients.create(target.uri())) {
            MongoCollection<Document> items = client.getDatabase(target.database()).getCollection("items");
            assertThat(items.countDocuments()).isEqualTo(1);
            assertThat(items.find(new Document("_id", "untouched")).first()).containsEntry("marker", true);
        }
    }

    @Test void snapshotsBeforeAppendingToSameDatabaseAndValidateOnlyDoesNotWrite(@TempDir Path temp) throws Exception {
        String database = "generation_" + UUID.randomUUID().toString().replace("-", "");
        Endpoint endpoint = new Endpoint(MONGO.getReplicaSetUrl(), database);
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            client.getDatabase(database).getCollection("items").insertMany(List.of(
                    new Document("_id", new ObjectId("000000000000000000000001")).append("staticValue", "A"),
                    new Document("_id", new ObjectId("000000000000000000000002")).append("staticValue", "B")));
        }
        Path config = temp.resolve("generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                templateSelection: SEQUENTIAL
                batchSize: 2
                parallelism: 3
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 3
                    fields:
                      /_id: { kind: objectId }
                      /ordinal: { kind: sequence, start: 0, step: 1 }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport validation = run(endpoint, config, true, temp.resolve("validation.json"));
        assertThat(validation.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(validation.safeToRetry()).isTrue();
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            assertThat(client.getDatabase(database).getCollection("items").countDocuments()).isEqualTo(2);
        }

        GenerationReport report = run(endpoint, config, false, temp.resolve("generation.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collections().getFirst().written()).isEqualTo(3);
        assertThat(report.safeToRetry()).isFalse();
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            MongoCollection<Document> items = client.getDatabase(database).getCollection("items");
            assertThat(items.countDocuments()).isEqualTo(5);
            List<Document> generated = items.find(new Document("generated", true)).sort(Sorts.ascending("ordinal")).into(new ArrayList<>());
            assertThat(generated).extracting(d -> d.getString("staticValue")).containsExactly("A", "B", "A");
            assertThat(generated).extracting(d -> d.getLong("ordinal")).containsExactly(0L, 1L, 2L);
        }
    }

    @Test void randomStringIdsAreUniquePerBatchAndExistingDocumentsAreFullyReplaced(@TempDir Path temp) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint source = new Endpoint(MONGO.getReplicaSetUrl(), "generation_source_" + suffix);
        Endpoint target = new Endpoint(MONGO.getReplicaSetUrl(), "generation_target_" + suffix);
        try (MongoClient client = MongoClients.create(source.uri())) {
            client.getDatabase(source.database()).getCollection("items")
                    .insertOne(new Document("_id", "template").append("templateValue", true));
            MongoCollection<Document> targetItems = client.getDatabase(target.database()).getCollection("items");
            targetItems.insertOne(new Document("_id", "target-template"));
            targetItems.createIndex(new Document("GFCUS", 1), new IndexOptions().unique(true));
        }
        Path config = temp.resolve("random-id-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                batchSize: 4
                parallelism: 3
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 4
                    fields:
                      /_id: { kind: randomString, alphabet: CUSTOM, characters: "AB", length: 2 }
                      /GFCUS: { kind: ref, path: /_id }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport first = run(source, target, config, false, temp.resolve("first.json"));
        assertThat(first.status()).isEqualTo(OperationStatus.SUCCESS);

        try (MongoClient client = MongoClients.create(target.uri())) {
            MongoCollection<Document> items = client.getDatabase(target.database()).getCollection("items");
            Document generated = items.find(new Document("generated", true)).first();
            items.updateOne(new Document("_id", generated.get("_id")), new Document("$set", new Document("stale", true)));
        }

        GenerationReport second = run(source, target, config, false, temp.resolve("second.json"));

        assertThat(second.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(second.collections().getFirst().written()).isEqualTo(4);
        try (MongoClient client = MongoClients.create(target.uri())) {
            List<Document> generated = client.getDatabase(target.database()).getCollection("items")
                    .find(new Document("generated", true)).into(new ArrayList<>());
            assertThat(generated).hasSize(4);
            assertThat(generated).extracting(document -> document.getString("_id")).doesNotHaveDuplicates();
            assertThat(generated).allSatisfy(document -> {
                assertThat(document.getString("GFCUS")).isEqualTo(document.getString("_id"));
                assertThat(document).doesNotContainKey("stale");
            });
        }
    }

    @Test void shuffledTemplatesBuildCompositeIdsAndExactUpsertsReplaceExistingDocuments(@TempDir Path temp) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint source = new Endpoint(MONGO.getReplicaSetUrl(), "generation_composite_source_" + suffix);
        Endpoint target = new Endpoint(MONGO.getReplicaSetUrl(), "generation_composite_target_" + suffix);
        try (MongoClient client = MongoClients.create(source.uri())) {
            client.getDatabase(source.database()).getCollection("items").insertMany(List.of(
                    new Document("_id", "1").append("templateCode", "A").append("exact", Decimal128.parse("1.10")),
                    new Document("_id", "2").append("templateCode", "B").append("exact", Decimal128.parse("2.20")),
                    new Document("_id", "3").append("templateCode", "C").append("exact", Decimal128.parse("3.30"))));
            client.getDatabase(target.database()).getCollection("items")
                    .insertOne(new Document("_id", "target-placeholder"));
        }
        Path config = temp.resolve("composite-id-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                templateSelection: SHUFFLED_CYCLE
                batchSize: 3
                parallelism: 3
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 3
                    fields:
                      /generatedKey: { kind: randomString, alphabet: CUSTOM, characters: "AB", length: 2 }
                      /_id:
                        kind: concat
                        parts:
                          - { kind: ref, path: /generatedKey }
                          - { kind: literal, value: "|" }
                          - { kind: ref, path: /templateCode }
                      /ordinal: { kind: sequence, start: 0, step: 1 }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport first = run(source, target, config, false, temp.resolve("composite-first.json"));

        assertThat(first.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(first.templateSelection()).isEqualTo(TemplateSelection.SHUFFLED_CYCLE);
        List<Document> initiallyGenerated;
        try (MongoClient client = MongoClients.create(target.uri())) {
            initiallyGenerated = client.getDatabase(target.database()).getCollection("items")
                    .find(new Document("generated", true)).sort(Sorts.ascending("ordinal")).into(new ArrayList<>());
            assertThat(initiallyGenerated).extracting(document -> document.getString("templateCode"))
                    .containsExactlyInAnyOrder("A", "B", "C");
            assertThat(initiallyGenerated).extracting(document -> document.getString("_id")).doesNotHaveDuplicates();
            assertThat(initiallyGenerated).allSatisfy(document -> {
                assertThat(document.getString("_id")).endsWith("|" + document.getString("templateCode"));
                assertThat(document.get("exact")).isInstanceOf(Decimal128.class);
            });
            client.getDatabase(target.database()).getCollection("items").updateOne(
                    new Document("_id", initiallyGenerated.getFirst().getString("_id")),
                    new Document("$set", new Document("stale", true)));
        }

        GenerationReport second = run(source, target, config, false, temp.resolve("composite-second.json"));

        assertThat(second.status()).isEqualTo(OperationStatus.SUCCESS);
        try (MongoClient client = MongoClients.create(target.uri())) {
            List<Document> regenerated = client.getDatabase(target.database()).getCollection("items")
                    .find(new Document("generated", true)).sort(Sorts.ascending("ordinal")).into(new ArrayList<>());
            assertThat(regenerated).hasSize(3).noneSatisfy(document -> assertThat(document).containsKey("stale"));
            assertThat(regenerated).extracting(document -> document.getString("_id"))
                    .containsExactlyElementsOf(initiallyGenerated.stream().map(document -> document.getString("_id")).toList());
        }
    }

    @Test void batchedParallelWritesKeepExactCountAndUniqueSecondaryKeys(@TempDir Path temp) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint source = new Endpoint(MONGO.getReplicaSetUrl(), "generation_batched_source_" + suffix);
        Endpoint target = new Endpoint(MONGO.getReplicaSetUrl(), "generation_batched_target_" + suffix);
        try (MongoClient client = MongoClients.create(source.uri())) {
            client.getDatabase(source.database()).getCollection("items")
                    .insertOne(new Document("templateValue", true));
            MongoCollection<Document> targetItems = client.getDatabase(target.database()).getCollection("items");
            targetItems.insertOne(new Document("_id", "pre-existing").append("code", "pre-existing-code"));
            targetItems.createIndex(new Document("code", 1), new IndexOptions().unique(true));
        }
        Path config = temp.resolve("batched-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                templateSelection: SEQUENTIAL
                batchSize: 4
                parallelism: 4
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 8
                    fields:
                      /_id: { kind: objectId }
                      /code: { kind: sequence, start: 0, step: 1 }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport report = run(source, target, config, false, temp.resolve("batched.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collections().getFirst().written()).isEqualTo(8);
        try (MongoClient client = MongoClients.create(target.uri())) {
            MongoCollection<Document> items = client.getDatabase(target.database()).getCollection("items");
            assertThat(items.countDocuments()).isEqualTo(9);
            List<Document> generated = items.find(new Document("generated", true))
                    .sort(Sorts.ascending("code")).into(new ArrayList<>());
            assertThat(generated).hasSize(8);
            assertThat(generated).extracting(document -> document.getLong("code"))
                    .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
        }
    }

    @Test void streamsOnlyTheSnapshotPrefixThatFitsTheWorkingLimit(@TempDir Path temp) throws Exception {
        String database = "generation_bounded_" + UUID.randomUUID().toString().replace("-", "");
        Endpoint endpoint = new Endpoint(MONGO.getReplicaSetUrl(), database);
        String padding = "x".repeat(600_000);
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            client.getDatabase(database).getCollection("items").insertMany(List.of(
                    new Document("_id", new ObjectId("000000000000000000000001")).append("template", "A").append("padding", padding),
                    new Document("_id", new ObjectId("000000000000000000000002")).append("template", "B").append("padding", padding)));
        }
        Path config = temp.resolve("bounded-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                batchSize: 2
                parallelism: 2
                maxWorkingMegabytes: 1
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 2
                    fields:
                      /_id: { kind: objectId }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport report = run(endpoint, config, false, temp.resolve("bounded-generation.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collections().getFirst().snapshotTemplates()).isEqualTo(1);
        assertThat(report.collections().getFirst().snapshotBytes()).isLessThanOrEqualTo(1_038_090);
        assertThat(report.collections().getFirst().snapshotTruncated()).isTrue();
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("_id analysis", "validate-only coverage", "stored snapshot prefix"));
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            List<Document> generated = client.getDatabase(database).getCollection("items")
                    .find(new Document("generated", true)).into(new ArrayList<>());
            assertThat(generated).hasSize(2);
            assertThat(generated).extracting(document -> document.getString("template")).containsOnly("A");
        }
    }

    @Test void constantLiteralIdRequiresOptInAndUpsertsTheSameDocument(@TempDir Path temp) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Endpoint source = new Endpoint(MONGO.getReplicaSetUrl(), "generation_unproven_source_" + suffix);
        Endpoint target = new Endpoint(MONGO.getReplicaSetUrl(), "generation_unproven_target_" + suffix);
        try (MongoClient client = MongoClients.create(source.uri())) {
            client.getDatabase(source.database()).getCollection("items")
                    .insertOne(new Document("_id", "template").append("templateValue", true));
            client.getDatabase(target.database()).getCollection("items")
                    .insertMany(List.of(new Document("_id", "target-placeholder"),
                            new Document("_id", "repeated").append("stale", true)));
        }
        Path config = temp.resolve("unproven-id-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                batchSize: 2
                parallelism: 1
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 3
                    fields:
                      /_id: { kind: literal, value: repeated }
                      /ordinal: { kind: sequence, start: 0, step: 1 }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport strict = run(source, target, config, false, temp.resolve("strict.json"));
        assertThat(strict.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(strict.safeToRetry()).isTrue();
        assertThat(strict.errors().getFirst().message()).contains("Cannot prove generated uniqueness");

        GenerationReport report = run(source, target, config, false, true, temp.resolve("unproven.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.allowUnprovenIds()).isTrue();
        assertThat(report.collections().getFirst().written()).isEqualTo(3);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("ID UNIQUENESS PROOF DISABLED"));
        try (MongoClient client = MongoClients.create(target.uri())) {
            MongoCollection<Document> items = client.getDatabase(target.database()).getCollection("items");
            assertThat(items.countDocuments()).isEqualTo(2);
            assertThat(items.find(new Document("_id", "repeated")).first())
                    .containsEntry("ordinal", 2L)
                    .containsEntry("generated", true)
                    .doesNotContainKey("stale");
        }
    }

    @Test void writesOneSharedDatePerIterationAcrossCollectionsAndFormats(@TempDir Path temp) throws Exception {
        String database = "generation_shared_dates_" + UUID.randomUUID().toString().replace("-", "");
        Endpoint endpoint = new Endpoint(MONGO.getReplicaSetUrl(), database);
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            client.getDatabase(database).getCollection("events")
                    .insertOne(new Document("template", true));
            client.getDatabase(database).getCollection("legacyEvents")
                    .insertOne(new Document("template", true));
        }
        Path config = temp.resolve("shared-date-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                batchSize: 2
                parallelism: 3
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                sharedDates:
                  operationDate:
                    kind: randomRange
                    from: 2025-01-01T00:00:00Z
                    to: 2026-12-31T23:59:59.999Z
                collections:
                  - name: events
                    count: 3
                    fields:
                      /_id: { kind: objectId }
                      /ordinal: { kind: sequence, start: 0, step: 1 }
                      /operationDate:
                        kind: dateTime
                        source: { kind: shared, name: operationDate }
                        output: BSON_DATE
                      /operationText:
                        kind: dateTime
                        source: { kind: shared, name: operationDate }
                        output: STRING
                        pattern: "uuuu-MM-dd'T'HH:mm:ss.SSSX"
                      /generated: { kind: literal, value: true }
                  - name: legacyEvents
                    count: 3
                    fields:
                      /_id: { kind: objectId }
                      /ordinal: { kind: ref, collection: events, path: /ordinal, bsonType: INT32 }
                      /operationDate:
                        kind: dateTime
                        source: { kind: shared, name: operationDate }
                        output: BSON_DATE
                      /legacyDate:
                        kind: dateTime
                        source: { kind: shared, name: operationDate }
                        output: STRING
                        pattern: "'1'yyMMdd"
                      /legacyDateDouble:
                        kind: dateTime
                        source: { kind: shared, name: operationDate }
                        output: DOUBLE
                        pattern: "'1'yyMMdd"
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport report = run(endpoint, config, false, temp.resolve("shared-date-generation.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            MongoDatabase target = client.getDatabase(database);
            List<Document> events = target.getCollection("events").find(new Document("generated", true))
                    .sort(Sorts.ascending("ordinal")).into(new ArrayList<>());
            List<Document> legacyEvents = target.getCollection("legacyEvents").find(new Document("generated", true))
                    .sort(Sorts.ascending("ordinal")).into(new ArrayList<>());
            DateTimeFormatter iso = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX")
                    .withZone(ZoneOffset.UTC);
            DateTimeFormatter legacy = DateTimeFormatter.ofPattern("'1'yyMMdd").withZone(ZoneOffset.UTC);
            assertThat(events).hasSize(3);
            assertThat(legacyEvents).hasSize(3);
            for (int i = 0; i < events.size(); i++) {
                assertThat(events.get(i).getLong("ordinal")).isEqualTo((long) i);
                assertThat(legacyEvents.get(i).getInteger("ordinal")).isEqualTo(i);
                Date eventDate = events.get(i).getDate("operationDate");
                Date legacyDate = legacyEvents.get(i).getDate("operationDate");
                Instant instant = eventDate.toInstant();
                assertThat(legacyDate).isEqualTo(eventDate);
                assertThat(events.get(i).getString("operationText")).isEqualTo(iso.format(instant));
                assertThat(legacyEvents.get(i).getString("legacyDate")).isEqualTo(legacy.format(instant));
                assertThat(legacyEvents.get(i).getDouble("legacyDateDouble")).isEqualTo(Double.parseDouble(legacy.format(instant)));
            }
        }
    }

    @Test void weightedChoiceEvaluatesNestedRulesAsScalarBsonIds(@TempDir Path temp) throws Exception {
        String database = "generation_weighted_rules_" + UUID.randomUUID().toString().replace("-", "");
        Endpoint endpoint = new Endpoint(MONGO.getReplicaSetUrl(), database);
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            client.getDatabase(database).getCollection("items")
                    .insertOne(new Document("_id", "template").append("templateValue", true));
        }
        Path config = temp.resolve("weighted-rule-generation.yml");
        Files.writeString(config, """
                version: 1
                seed: 12345
                batchSize: 8
                parallelism: 2
                maxWorkingMegabytes: 10
                maxInFlightMegabytes: 2
                collections:
                  - name: items
                    count: 16
                    fields:
                      /_id:
                        kind: weightedChoice
                        choices:
                          - { value: { kind: randomAlphaNumStringBetween, min: 0, max: 2, length: 2 }, weight: 1 }
                          - { value: { kind: randomAlphaNumStringBetween, min: 36, max: 38, length: 2 }, weight: 1 }
                      /generated: { kind: literal, value: true }
                """);

        GenerationReport report = run(endpoint, config, false, temp.resolve("weighted-rule-generation.json"));

        assertThat(report.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(report.collections().getFirst().written()).isEqualTo(16);
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("POSSIBLE _id COLLISIONS", "keyspace=unknown", "risk=unknown"));
        try (MongoClient client = MongoClients.create(endpoint.uri())) {
            List<Document> generated = client.getDatabase(database).getCollection("items")
                    .find(new Document("generated", true)).into(new ArrayList<>());
            assertThat(generated).isNotEmpty().allSatisfy(document ->
                    assertThat(document.getString("_id")).matches("0[01]|1[01]"));
        }
    }

    private GenerationReport run(Endpoint endpoint, Path config, boolean validateOnly, Path report) {
        return run(endpoint, endpoint, config, validateOnly, report);
    }

    private GenerationReport run(Endpoint sourceEndpoint, Endpoint targetEndpoint, Path config,
                                 boolean validateOnly, Path report) {
        return run(sourceEndpoint, targetEndpoint, config, validateOnly, false, report);
    }

    private GenerationReport run(Endpoint sourceEndpoint, Endpoint targetEndpoint, Path config,
                                 boolean validateOnly, boolean allowUnprovenIds, Path report) {
        return run(sourceEndpoint, targetEndpoint, config, new GenerationOptions(validateOnly, allowUnprovenIds), report);
    }

    private GenerationReport run(Endpoint sourceEndpoint, Endpoint targetEndpoint, Path config,
                                 GenerationOptions options, Path report) {
        MongoGenerationSource source = new MongoGenerationSource(sourceEndpoint);
        MongoGenerationTarget target = new MongoGenerationTarget(targetEndpoint);
        GenerationOrchestrator service = new GenerationOrchestrator(source, target, new GenerationConfigReader(config),
                new FileTemplateCatalogFactory(), new MongoGenerationBsonEngine(),
                new JsonReportWriter(report), () -> false);
        return service.generate(new GenerationCommand(sourceEndpoint, targetEndpoint, options));
    }
}
