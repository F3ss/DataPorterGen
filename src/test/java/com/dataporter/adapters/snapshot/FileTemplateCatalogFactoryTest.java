package com.dataporter.adapters.snapshot;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.TemplateQuery;
import com.dataporter.generation.ports.out.GenerationSource;
import com.dataporter.generation.ports.out.TemplateCatalog;
import com.dataporter.generation.domain.GenerationSourceInspection;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.ports.out.BatchCursor;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FileTemplateCatalogFactoryTest {
    @Test void passesEachCollectionQueryToTheGenerationSource() {
        BsonPayload document = payload(5);
        TrackingSource source = new TrackingSource(Map.of("items", List.of(List.of(document))));
        TemplateQuery query = new TemplateQuery(Map.of("enabled", true));
        CollectionGenerationSpec collection = new CollectionGenerationSpec("items", 1, query, Map.of());

        try (TemplateCatalog ignored = new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection), 1024)) {
            assertThat(source.query("items")).isSameAs(query);
        }
    }

    @Test void snapshotsWithRandomAccessAndRemovesOnlyItsRunDirectory() throws Exception {
        Set<Path> before = snapshotDirectories();
        BsonPayload first = payload(5);
        BsonPayload second = payload(6);
        TrackingSource source = new TrackingSource(Map.of("items", List.of(List.of(first, second))));

        TemplateCatalog catalog = new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection("items")), 1024);

        assertThat(catalog.count("items")).isEqualTo(2);
        assertThat(catalog.bytes("items")).isEqualTo(35);
        assertThat(catalog.truncated("items")).isFalse();
        assertThat(catalog.get("items", 0)).isEqualTo(first);
        assertThat(catalog.get("items", 1)).isEqualTo(second);
        catalog.close();
        assertThat(snapshotDirectories()).isEqualTo(before);
    }

    @Test void stopsAtFirstDocumentOutsideShareAndClosesCursor() {
        BsonPayload fitting = payload(20);
        BsonPayload outside = payload(30);
        TrackingSource source = new TrackingSource(Map.of("items", List.of(
                List.of(fitting, fitting), List.of(outside), List.of(fitting))));

        try (TemplateCatalog catalog = new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection("items")), 100)) {
            assertThat(catalog.count("items")).isEqualTo(2);
            assertThat(catalog.bytes("items")).isEqualTo(64);
            assertThat(catalog.truncated("items")).isTrue();
        }

        assertThat(source.cursor("items").nextCalls).isEqualTo(2);
        assertThat(source.cursor("items").closed).isTrue();
    }

    @Test void acceptsExactUsableBoundaryAndLeavesOnePercentReserve() throws Exception {
        Set<Path> before = snapshotDirectories();
        TrackingSource exact = new TrackingSource(Map.of("items", List.of(List.of(payload(87)))));

        try (TemplateCatalog catalog = new FileTemplateCatalogFactory().snapshot(exact,
                List.of(collection("items")), 100)) {
            assertThat(catalog.bytes("items")).isEqualTo(99);
            assertThat(catalog.truncated("items")).isFalse();
        }

        TrackingSource inReserve = new TrackingSource(Map.of("items", List.of(List.of(payload(88)))));
        assertThatThrownBy(() -> new FileTemplateCatalogFactory().snapshot(inReserve,
                List.of(collection("items")), 100))
                .isInstanceOf(SourceInspectionException.class)
                .hasMessageContaining("first template");
        assertThat(inReserve.cursor("items").closed).isTrue();
        assertThat(snapshotDirectories()).isEqualTo(before);
    }

    @Test void givesCollectionsEqualSharesWithoutRedistribution() {
        BsonPayload document = payload(5);
        TrackingSource source = new TrackingSource(Map.of(
                "first", List.of(List.of(document, document, document, document)),
                "second", List.of(List.of(document, document, document, document))));

        try (TemplateCatalog catalog = new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection("first"), collection("second")), 104)) {
            assertThat(catalog.count("first")).isEqualTo(3);
            assertThat(catalog.count("second")).isEqualTo(3);
            assertThat(catalog.bytes("first")).isEqualTo(51);
            assertThat(catalog.bytes("second")).isEqualTo(51);
            assertThat(catalog.bytes("first") + catalog.bytes("second")).isLessThanOrEqualTo(102);
            assertThat(catalog.truncated("first")).isTrue();
            assertThat(catalog.truncated("second")).isTrue();
        }
    }

    @Test void emptyCollectionFailsAndCleansSnapshot() throws Exception {
        Set<Path> before = snapshotDirectories();
        TrackingSource source = new TrackingSource(Map.of("items", List.of()));

        assertThatThrownBy(() -> new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection("items")), 100))
                .isInstanceOf(SourceInspectionException.class)
                .hasMessageContaining("no template documents");

        assertThat(source.cursor("items").closed).isTrue();
        assertThat(snapshotDirectories()).isEqualTo(before);
    }

    @Test void concurrentRandomAccessReturnsStableTemplates() throws Exception {
        List<BsonPayload> documents = List.of(payload(5), payload(6), payload(7), payload(8));
        TrackingSource source = new TrackingSource(Map.of("items", List.of(documents)));
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (TemplateCatalog catalog = new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection("items")), 1024)) {
            try (java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
                for (int worker = 0; worker < 8; worker++) {
                    final int seed = worker;
                    workers.submit(() -> {
                        try {
                            Random random = new Random(seed);
                            for (int read = 0; read < 2_000; read++) {
                                int ordinal = random.nextInt(documents.size());
                                assertThat(catalog.get("items", ordinal)).isEqualTo(documents.get(ordinal));
                            }
                        } catch (Throwable failure) { failures.add(failure); }
                    });
                }
            }
            assertThat(failures).isEmpty();
        }
    }

    @Test void closeIsIdempotentRejectsFurtherReadsAndRemovesFiles() throws Exception {
        Set<Path> before = snapshotDirectories();
        TrackingSource source = new TrackingSource(Map.of("items", List.of(List.of(payload(5), payload(6)))));

        TemplateCatalog catalog = new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection("items")), 100);
        assertThat(catalog.get("items", 1)).isEqualTo(payload(6));

        catalog.close();
        catalog.close();

        assertThatThrownBy(() -> catalog.get("items", 0)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshotDirectories()).isEqualTo(before);
    }

    @Test void snapshotFilesAreOwnerOnly() throws Exception {
        TrackingSource source = new TrackingSource(Map.of("items", List.of(List.of(payload(5), payload(6)))));
        try (TemplateCatalog catalog = new FileTemplateCatalogFactory().snapshot(source,
                List.of(collection("items")), 100)) {
            assertThat(catalog.count("items")).isEqualTo(2);
            Path directory = snapshotDirectories().stream().findAny().orElseThrow();
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (Path path : paths.toList()) {
                    var permissions = java.nio.file.Files.getPosixFilePermissions(path);
                    assertThat(permissions.stream().filter(permission ->
                            permission.name().startsWith("GROUP") || permission.name().startsWith("OTHERS")))
                            .as("permissions of %s", path).isEmpty();
                }
            }
        }
    }

    @Test void restrictVerifiesOwnerOnlyAndFailsClosed() throws Exception {
        Path missing = Path.of("missing-" + UUID.randomUUID());
        assertThatThrownBy(() -> FileTemplateCatalogFactory.restrict(missing))
                .isInstanceOf(SourceInspectionException.class)
                .hasMessageContaining("permissions");

        Path directory = Files.createTempDirectory("restrict-test-");
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
            FileTemplateCatalogFactory.restrict(directory);
            assertThat(Files.getPosixFilePermissions(directory)).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    private CollectionGenerationSpec collection(String name) {
        return new CollectionGenerationSpec(name, 1, Map.of());
    }

    private BsonPayload payload(int size) {
        return new BsonPayload(new byte[size]);
    }

    private Set<Path> snapshotDirectories() throws Exception {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var entries = Files.list(temp)) {
            return entries.filter(path -> Files.isDirectory(path) && path.getFileName().toString().startsWith("dataporter-generation-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
    private static final class TrackingSource implements GenerationSource {
        private final Map<String, List<List<BsonPayload>>> batches;
        private final Map<String, TrackingCursor> cursors = new HashMap<>();
        private final Map<String, TemplateQuery> queries = new HashMap<>();

        private TrackingSource(Map<String, List<List<BsonPayload>>> batches) { this.batches = batches; }
        TrackingCursor cursor(String collection) { return cursors.get(collection); }
        TemplateQuery query(String collection) { return queries.get(collection); }
        public void checkConnection() { }
        public void checkReadable() { }
        public boolean databaseExists() { return true; }
        public GenerationSourceInspection inspect() { return new GenerationSourceInspection(List.of(), List.of()); }
        public BatchCursor openBatches(String collection, int batchSize) {
            TrackingCursor cursor = new TrackingCursor(collection, batches.get(collection));
            cursors.put(collection, cursor);
            return cursor;
        }
        public BatchCursor openTemplateBatches(String collection, TemplateQuery query, int batchSize) {
            queries.put(collection, query);
            return openBatches(collection, batchSize);
        }
        public void close() { }
    }

    private static final class TrackingCursor implements BatchCursor {
        private final String collection;
        private final Iterator<List<BsonPayload>> batches;
        private int nextCalls;
        private boolean closed;

        private TrackingCursor(String collection, List<List<BsonPayload>> batches) {
            this.collection = collection;
            this.batches = batches.iterator();
        }
        public DataBatch next() {
            nextCalls++;
            if (!batches.hasNext()) return null;
            List<BsonPayload> documents = batches.next();
            return new DataBatch(collection, documents, documents.stream().mapToLong(BsonPayload::size).sum());
        }
        public void close() { closed = true; }
    }
}
