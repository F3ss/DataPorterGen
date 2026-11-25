package com.dataporter.adapters.config;

import com.dataporter.generation.application.GenerationConfigReconciler;
import com.dataporter.generation.ports.out.GenerationSchemaSource;
import com.dataporter.shared.error.ConfigurationException;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.ports.out.CancellationToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationConfigReconcilerTest {
    @TempDir Path directory;

    @Test void archivesBlockRulesAndRefreshesHintsWithoutRewritingOtherYaml() throws Exception {
        Path file = directory.resolve("generation.yml");
        String original = """
                # Operator note 📄
                version: 1
                collections:
                  - name: people
                    count: 4
                    fields:
                      /old:
                        kind: literal
                        value: |-
                          old text
                          second line
                      # comment for email
                      /email: { kind: literal, value: "a@b" } # preserve
                      /address/city: { kind: literal, value: "Tashkent" }
                """.replace("\n", "\r\n");
        Files.writeString(file, original);
        var result = reconcile(file, Map.of("people", new GenerationSchemaSource.FieldSchema(Map.of(
                "/_id", Set.of("OBJECT_ID"), "/email", Set.of("STRING"), "/address", Set.of("DOCUMENT"),
                "/address/city", Set.of("STRING"), "/address/zip", Set.of("INT32", "STRING")),
                Set.of("/email", "/address/city"), true)));

        String updated = Files.readString(file);
        assertThat(result.updated()).isTrue();
        assertThat(result.obsolete()).isEqualTo(1);
        assertThat(result.missing()).isEqualTo(1);
        assertThat(updated).contains(
                "# Operator note 📄\r\n",
                "  # /address/zip: INT32 | STRING\r\n",
                "      # /old:\r\n      #   kind: literal\r\n      #   value: |-\r\n",
                "      #     old text\r\n      #     second line\r\n",
                "      # comment for email\r\n      /email: { kind: literal, value: \"a@b\" } # preserve\r\n")
                .doesNotContain("# /address:", "# /_id:");
        assertThat(new GenerationConfigReader(file).read().collections().getFirst().fields().keySet())
                .containsExactly("/email", "/address/city");

        var refreshedSchema = Map.of("people", new GenerationSchemaSource.FieldSchema(Map.of(
                "/email", Set.of("STRING"), "/address/city", Set.of("STRING")),
                Set.of("/email", "/address/city"), true));
        assertThat(reconcile(file, refreshedSchema).updated()).isTrue();
        assertThat(Files.readString(file)).doesNotContain("/address/zip").contains("# /old:");
        assertThat(reconcile(file, refreshedSchema).updated()).isFalse();
    }

    @Test void preservesYamlFlowCollectionsAndRemovesCommasAroundArchivedRules() throws Exception {
        Path file = directory.resolve("generation.yaml");
        Files.writeString(file, """
                version: 1
                collections: [{ name: items, count: 1, fields: {
                  /obsolete: { kind: literal, value: { nested: [1, 2] } },
                  /active: { kind: literal, value: 'a' }, # preserve
                  /trailing: { kind: literal, value: 'b' },
                }}, { name: empty, count: 0, fields: { /allOld: { kind: objectId } } }]
                """);
        var schema = Map.of(
                "items", new GenerationSchemaSource.FieldSchema(
                        Map.of("/fresh", Set.of("BOOLEAN")), Set.of("/active"), true),
                "empty", new GenerationSchemaSource.FieldSchema(Map.of(), Set.of(), true));
        assertThat(reconcile(file, schema).obsolete()).isEqualTo(3);
        String updated = Files.readString(file);
        assertThat(updated).contains("# /obsolete: { kind: literal, value: { nested: [1, 2] } }",
                "# /trailing:", "# /allOld:", "# /fresh: BOOLEAN", "# preserve");
        var collections = new GenerationConfigReader(file).read().collections();
        assertThat(collections.getFirst().fields()).containsOnlyKeys("/active");
        assertThat(collections.getLast().fields()).isEmpty();
        assertThat(reconcile(file, schema).updated()).isFalse();
    }

    @Test void preservesDependenciesAndWritesEmptyBlockMappingWhenAllRulesAreObsolete() throws Exception {
        Path file = directory.resolve("generation.yml");
        Files.writeString(file, """
                version: 1
                collections:
                  - name: first
                    count: 1
                    fields:
                      /raw: { kind: literal, value: 1 }
                      /alias: { kind: ref, path: /raw }
                      /obsolete: { kind: objectId }
                  - name: second
                    count: 1
                    fields:
                      /number: { kind: ref, collection: first, path: /alias }
                  - name: third
                    count: 1
                    fields:
                      /items/3/obsolete:
                        kind: objectId
                  - name: empty
                    count: 1
                    fields: { /keep: { kind: objectId } }
                """);
        var schemas = Map.of(
                "first", new GenerationSchemaSource.FieldSchema(Map.of(), Set.of(), true),
                "second", new GenerationSchemaSource.FieldSchema(Map.of(), Set.of("/number"), true),
                "third", new GenerationSchemaSource.FieldSchema(Map.of("/items", Set.of("ARRAY")), Set.of(), true),
                "empty", new GenerationSchemaSource.FieldSchema(Map.of("/declared", Set.of("STRING")), Set.of(), false));
        var result = reconcile(file, schemas);

        assertThat(result.retained().get("first")).containsExactlyInAnyOrder("/raw", "/alias");
        assertThat(result.emptyCollections()).containsExactly("empty");
        var collections = new GenerationConfigReader(file).read().collections();
        assertThat(collections.get(0).fields()).containsOnlyKeys("/raw", "/alias");
        assertThat(collections.get(2).fields()).isEmpty();
        assertThat(collections.get(3).fields()).containsOnlyKeys("/keep");
        assertThat(Files.readString(file)).contains("# /obsolete:", "# /declared: STRING", "# /items: ARRAY");
        assertThat(reconcile(file, schemas).updated()).isFalse();
    }

    @Test void sourceFailureLeavesConfigUntouchedAndJsonIsRejectedBeforeSourceAccess() throws Exception {
        String yaml = "version: 1\ncollections: [{ name: items, count: 1, fields: {} }]\n";
        Path file = directory.resolve("generation.yml");
        Files.writeString(file, yaml);
        assertThatThrownBy(() -> reconcile(file, Map.of()))
                .isInstanceOf(SourceInspectionException.class);
        assertThat(Files.readString(file)).isEqualTo(yaml);

        var reconciler = new GenerationConfigReconciler(() -> {
            throw new AssertionError("Source must not be opened");
        }, new GenerationConfigReader(directory.resolve("generation.json")), () -> false);
        assertThatThrownBy(reconciler::reconcile).isInstanceOf(ConfigurationException.class).hasMessageContaining("requires");
    }

    private GenerationConfigReconciler.Result reconcile(Path file, Map<String, GenerationSchemaSource.FieldSchema> schemas) {
        return new GenerationConfigReconciler(() -> new GenerationSchemaSource() {
            @Override public void checkConnection() {}
            @Override public void close() {}
            @Override public FieldSchema inspectFields(String collection, Set<String> paths, int batchSize,
                                                       CancellationToken cancellation) {
                FieldSchema schema = schemas.get(collection);
                if (schema == null) throw new SourceInspectionException("Collection is unavailable");
                return schema;
            }
        }, new GenerationConfigReader(file), () -> false).reconcile();
    }
}
