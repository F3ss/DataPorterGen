package com.dataporter.adapters.config;

import com.dataporter.generation.application.GenerationSpecValidator;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.TemplateSelection;
import com.dataporter.generation.domain.TemplateQuery;
import com.dataporter.shared.error.ConfigurationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class GenerationConfigReaderTest {
    @Test void readsOptionalTemplateQueryAsImmutableYamlValueTree(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("query.yml");
        Files.writeString(file, """
                version: 1
                collections:
                  - name: items
                    count: 1
                    query:
                      "$and":
                        - { "profile.enabled": true }
                        - { code: { "$in": [A, null, 7, 2147483648, 1.5] } }
                    fields: {}
                  - name: unfiltered
                    count: 1
                    fields: {}
                """);

        var collections = new GenerationConfigReader(file).read().collections();

        TemplateQuery query = collections.getFirst().query();
        assertThat(query.isMatchAll()).isFalse();
        assertThat(query.document()).containsOnlyKeys("$and");
        assertThat(collections.get(1).query().isMatchAll()).isTrue();
        assertThatThrownBy(() -> query.document().put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsInvalidOrUnsafeTemplateQueries(@TempDir Path temp) throws Exception {
        List<String> invalidQueries = List.of(
                "true",
                "{ value: 9223372036854775808 }",
                "{ \"$where\": \"return true\" }",
                "{ \"$expr\": { \"$function\": { body: x, args: [], lang: js } } }",
                "{ field: { \"$accumulator\": true } }"
        );

        for (int i = 0; i < invalidQueries.size(); i++) {
            Path file = temp.resolve("invalid-query-" + i + ".yml");
            Files.writeString(file, "version: 1\ncollections: [{ name: items, count: 1, query: "
                    + invalidQueries.get(i) + ", fields: {} }]\n");

            assertThatThrownBy(() -> new GenerationConfigReader(file).read())
                    .as("query %s", invalidQueries.get(i))
                    .isInstanceOf(ConfigurationException.class);
        }
    }

    @Test void readsSharedDatesAndSharedDateSourcesStrictly(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("shared-dates.yml");
        Files.writeString(file, """
                version: 1
                sharedDates:
                  operationDate:
                    kind: randomRange
                    from: 2025-01-01T00:00:00Z
                    to: 2026-12-31T23:59:59.999Z
                  closingDate: { kind: fixed, value: 2030-12-31T23:59:59.123456Z }
                collections:
                  - name: events
                    count: 1
                    fields:
                      /createdAt:
                        kind: dateTime
                        source: { kind: shared, name: operationDate }
                        output: BSON_DATE
                """);

        var spec = new GenerationConfigReader(file).read();

        assertThat(spec.sharedDates().keySet()).containsExactly("operationDate", "closingDate");
        assertThat(spec.sharedDates().get("operationDate").source()).isInstanceOf(RandomDateRange.class);
        assertThat(spec.sharedDates().get("closingDate")).isEqualTo(new SharedDateDefinition(
                new FixedDate(java.time.Instant.parse("2030-12-31T23:59:59.123456Z"))));
        DateTime rule = (DateTime) spec.collections().getFirst().fields().get("/createdAt");
        assertThat(rule.source()).isEqualTo(new SharedDateRef("operationDate"));
    }

    @Test void rejectsReferencesAndUnknownPropertiesInSharedDateDefinitions(@TempDir Path temp) throws Exception {
        Path reference = temp.resolve("shared-ref.yml");
        Files.writeString(reference, """
                version: 1
                sharedDates: { operationDate: { kind: ref, path: /createdAt } }
                collections: [{ name: events, count: 1, fields: {} }]
                """);
        Path unknown = temp.resolve("shared-unknown.yml");
        Files.writeString(unknown, """
                version: 1
                sharedDates: { operationDate: { kind: fixed, value: 2030-01-01T00:00:00Z, extra: true } }
                collections: [{ name: events, count: 1, fields: {} }]
                """);

        assertThatThrownBy(() -> new GenerationConfigReader(reference).read())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("sharedDates.operationDate supports only fixed or randomRange");
        assertThatThrownBy(() -> new GenerationConfigReader(unknown).read())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown property root.sharedDates.operationDate.extra");
    }

    @Test void readsYamlStrictlyAndPreservesCollectionAndFieldOrder(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("generation.yml");
        Files.writeString(file, """
                version: 1
                seed: 42
                collections:
                  - name: customers
                    count: 3
                    fields:
                      /code: { kind: randomString, alphabet: UPPER_LATIN, length: 8 }
                      /active: { kind: randomBoolean, trueProbability: 0.75 }
                  - name: orders
                    count: 2
                    fields:
                      /customerId: { kind: ref, collection: customers, path: /_id }
                """);

        var spec = new GenerationConfigReader(file).read();

        assertThat(spec.seed()).isEqualTo(42);
        assertThat(spec.templateSelection()).isEqualTo(TemplateSelection.SHUFFLED_CYCLE);
        assertThat(spec.batchSize()).isEqualTo(1000);
        assertThat(spec.maxWorkingMegabytes()).isEqualTo(100);
        assertThat(spec.collections()).extracting(c -> c.name()).containsExactly("customers", "orders");
        assertThat(spec.collections().getFirst().fields().keySet()).containsExactly("/code", "/active");
        assertThat(spec.collections().getFirst().fields().get("/code")).isInstanceOf(GenerationRule.RandomString.class);
        assertThat(spec.configHash()).hasSize(64);
    }

    @Test void readsBothTemplateSelectionStrategiesAndRejectsUnknownValues(@TempDir Path temp) throws Exception {
        Path sequential = temp.resolve("sequential.yml");
        Files.writeString(sequential, "version: 1\ntemplateSelection: SEQUENTIAL\ncollections: [{ name: items, count: 1, fields: {} }]\n");
        Path shuffled = temp.resolve("shuffled.yml");
        Files.writeString(shuffled, "version: 1\ntemplateSelection: SHUFFLED_CYCLE\ncollections: [{ name: items, count: 1, fields: {} }]\n");
        Path invalid = temp.resolve("invalid-selection.yml");
        Files.writeString(invalid, "version: 1\ntemplateSelection: RANDOM\ncollections: [{ name: items, count: 1, fields: {} }]\n");

        assertThat(new GenerationConfigReader(sequential).read().templateSelection()).isEqualTo(TemplateSelection.SEQUENTIAL);
        assertThat(new GenerationConfigReader(shuffled).read().templateSelection()).isEqualTo(TemplateSelection.SHUFFLED_CYCLE);
        assertThatThrownBy(() -> new GenerationConfigReader(invalid).read())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("root.templateSelection has unsupported value RANDOM");
    }

    @Test void acceptsExplicitWorkingLimitBelowDefault(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("small-working-limit.yml");
        Files.writeString(file, """
                version: 1
                maxWorkingMegabytes: 1
                collections: [{ name: items, count: 1, fields: {} }]
                """);

        assertThat(new GenerationConfigReader(file).read().maxWorkingMegabytes()).isEqualTo(1);
    }

    @Test void readsJsonAndRejectsUnknownProperties(@TempDir Path temp) throws Exception {
        Path valid = temp.resolve("generation.json");
        Files.writeString(valid, """
                {"version":1,"collections":[{"name":"items","count":1,
                 "fields":{"/_id":{"kind":"objectId"}}}]}
                """);
        assertThat(new GenerationConfigReader(valid).read().collections()).hasSize(1);

        Path invalid = temp.resolve("invalid.yml");
        Files.writeString(invalid, """
                version: 1
                surprise: true
                collections: [{ name: items, count: 1, fields: {} }]
                """);
        assertThatThrownBy(() -> new GenerationConfigReader(invalid).read())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("Unknown property root.surprise");
    }

    @Test void rejectsLiteralIntegersOutsideBsonInt64Range(@TempDir Path temp) throws Exception {
        for (String value : List.of("9223372036854775808", "-9223372036854775809")) {
            Path file = temp.resolve("out-of-range-" + (value.startsWith("-") ? "negative" : "positive") + ".yml");
            Files.writeString(file, """
                    version: 1
                    collections:
                      - name: items
                        count: 1
                        fields:
                          /value: { kind: literal, value: %s }
                    """.formatted(value));

            assertThatThrownBy(() -> new GenerationConfigReader(file).read())
                    .as("literal %s", value)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("outside BSON int64 range");
        }
    }

    @Test void rejectsUnsupportedSchemaVersion(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("generation.yml");
        Files.writeString(file, "version: 2\ncollections: [{ name: items, count: 1, fields: {} }]\n");
        assertThatThrownBy(() -> new GenerationConfigReader(file).read())
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("version must be 1");
    }

    @Test void repositoryExampleParsesAndValidates() {
        var spec = new GenerationConfigReader(Path.of("generation.example.yml")).read();
        assertThatCode(() -> new GenerationSpecValidator().validate(spec)).doesNotThrowAnyException();
        var dockerFixture = new GenerationConfigReader(Path.of("docker/generation/generation.yml")).read();
        assertThatCode(() -> new GenerationSpecValidator().validate(dockerFixture)).doesNotThrowAnyException();
    }

    @Test void readsRandomAlphaNumStringBetweenWithRequiredIntegralProperties(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("alpha-num.yml");
        Files.writeString(file, """
                version: 1
                collections:
                  - name: items
                    count: 1
                    fields:
                      /code:
                        kind: randomAlphaNumStringBetween
                        min: 10000000
                        max: 1000000000
                        length: 6
                        nullProbability: 0.1
                        omitProbability: 0.2
                """);

        var rule = (RandomAlphaNumStringBetween) new GenerationConfigReader(file).read()
                .collections().getFirst().fields().get("/code");

        assertThat(rule.min()).isEqualTo(BigInteger.valueOf(10_000_000));
        assertThat(rule.max()).isEqualTo(BigInteger.valueOf(1_000_000_000));
        assertThat(rule.length()).isEqualTo(6);
        assertThat(rule.options()).isEqualTo(new RuleOptions(0.1, 0.2));
    }

    @Test void readsWeightedChoiceValuesAsNestedRulesOrLiteralShorthand(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("weighted-rules.yml");
        Files.writeString(file, """
                version: 1
                collections:
                  - name: QWE
                    count: 67000000
                    fields:
                      /_id:
                        kind: weightedChoice
                        choices:
                          - { value: { kind: randomAlphaNumStringBetween, min: 10000000, max: 1000000000, length: 6 }, weight: 500 }
                          - { value: fixed, weight: 1 }
                          - value: { kind: literal, value: { kind: domain-value, enabled: true } }
                            weight: 1
                """);

        WeightedChoice choice = (WeightedChoice) new GenerationConfigReader(file).read()
                .collections().getFirst().fields().get("/_id");

        assertThat(choice.choices()).hasSize(3);
        assertThat(choice.choices().get(0).value()).isInstanceOf(RandomAlphaNumStringBetween.class);
        assertThat(choice.choices().get(1).value()).isEqualTo(new Literal("fixed", RuleOptions.REQUIRED));
        assertThat(((Literal) choice.choices().get(2).value()).value())
                .isEqualTo(java.util.Map.of("kind", "domain-value", "enabled", true));
    }

    @Test void rejectsMalformedNestedWeightedChoiceRule(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("invalid-weighted-rule.yml");
        Files.writeString(file, """
                version: 1
                collections:
                  - name: items
                    count: 1
                    fields:
                      /code:
                        kind: weightedChoice
                        choices:
                          - { value: { kind: randomAlphaNumStringBetween, min: 0, length: 6 }, weight: 1 }
                """);

        assertThatThrownBy(() -> new GenerationConfigReader(file).read())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("choices[0].value.max is required");
    }

    @Test void rejectsMissingUnknownFractionalAndInvalidRandomAlphaNumProperties(@TempDir Path temp) throws Exception {
        List<String> invalidRules = List.of(
                "{ kind: randomAlphaNumStringBetween, max: 100, length: 2 }",
                "{ kind: randomAlphaNumStringBetween, min: 0, length: 2 }",
                "{ kind: randomAlphaNumStringBetween, min: 0, max: 100 }",
                "{ kind: randomAlphaNumStringBetween, min: 0, max: 100, length: 2, surprise: true }",
                "{ kind: randomAlphaNumStringBetween, min: 0.5, max: 100, length: 2 }",
                "{ kind: randomAlphaNumStringBetween, min: 0, max: 100.5, length: 2 }",
                "{ kind: randomAlphaNumStringBetween, min: -1, max: 100, length: 2 }",
                "{ kind: randomAlphaNumStringBetween, min: 10, max: 10, length: 2 }",
                "{ kind: randomAlphaNumStringBetween, min: 11, max: 10, length: 2 }",
                "{ kind: randomAlphaNumStringBetween, min: 0, max: 9223372036854775809, length: 13 }",
                "{ kind: randomAlphaNumStringBetween, min: 0, max: 2, length: 0 }",
                "{ kind: randomAlphaNumStringBetween, min: 0, max: 2, length: 16777217 }",
                "{ kind: randomAlphaNumStringBetween, min: 35, max: 37, length: 1 }"
        );

        for (int i = 0; i < invalidRules.size(); i++) {
            Path file = temp.resolve("invalid-alpha-num-" + i + ".yml");
            Files.writeString(file, "version: 1\ncollections: [{ name: items, count: 1, fields: { /code: "
                    + invalidRules.get(i) + " } }]\n");
            assertThatThrownBy(() -> new GenerationConfigReader(file).read())
                    .as("rule %s", invalidRules.get(i)).isInstanceOf(ConfigurationException.class);
        }
    }
}
