package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.TemplateSelection;
import com.dataporter.shared.error.ConfigurationException;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GenerationSpecValidatorTest {
    private static final RuleOptions REQUIRED = RuleOptions.REQUIRED;

    @Test void rejectsDownwardReferencesAndInsufficientParentCount() {
        var childRef = new Ref("orders", "/_id", MissingPolicy.ERROR, REQUIRED);
        assertThatThrownBy(() -> validator().validate(spec(
                collection("customers", 2, Map.of("/order", childRef)), collection("orders", 2, Map.of()))))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("not earlier");

        var parent = collection("customers", 1, Map.of());
        var child = collection("orders", 2, Map.of("/customer", new Ref("customers", "/_id", MissingPolicy.ERROR, REQUIRED)));
        assertThatThrownBy(() -> validator().validate(spec(parent, child)))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("count must be at least");
    }

    @Test void rejectsConflictingPointersAndLocalCycles() {
        assertThatThrownBy(() -> validator().validate(spec(collection("items", 1, Map.of(
                "/a", new Literal("x", REQUIRED), "/a/b", new Literal("y", REQUIRED))))))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("Conflicting field rules");

        assertThatThrownBy(() -> validator().validate(spec(collection("items", 1, Map.of(
                "/a", new Ref(null, "/b", MissingPolicy.ERROR, REQUIRED),
                "/b", new Ref(null, "/a", MissingPolicy.ERROR, REQUIRED))))))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("Cyclic field dependency");
    }

    @Test void validatesReferencesAndAutoSequencesInsideWeightedChoices() {
        WeightedChoice cyclic = new WeightedChoice(List.of(new Choice(
                new Ref(null, "/b", MissingPolicy.ERROR, REQUIRED), 1)), REQUIRED);
        assertThatThrownBy(() -> validator().validate(spec(collection("items", 1, Map.of(
                "/a", cyclic,
                "/b", new Ref(null, "/a", MissingPolicy.ERROR, REQUIRED))))))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("Cyclic field dependency");

        WeightedChoice auto = new WeightedChoice(List.of(new Choice(
                new Sequence(SequenceStart.AUTO_AFTER_TARGET_MAX, 0, 1, REQUIRED), 1)), REQUIRED);
        assertThatThrownBy(() -> validator().validate(spec(collection("items", 1, Map.of("/value", auto)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("AUTO_AFTER_TARGET_MAX sequence is unsupported inside weightedChoice");
    }

    @Test void validatesSharedDateReferencesWithoutCollectionOrderOrCountConstraints() {
        DateTime sharedDate = new DateTime(new SharedDateRef("operationDate"), DateOutput.STRING,
                "'1'yyMMdd", "UTC", "ROOT", REQUIRED);
        GenerationSpec valid = spec(Map.of("operationDate", new SharedDateDefinition(
                        new RandomDateRange(java.time.Instant.parse("2025-01-01T00:00:00Z"),
                                java.time.Instant.parse("2026-12-31T23:59:59.999Z")))),
                collection("first", 1, Map.of("/legacyDate", sharedDate)),
                collection("second", 100, Map.of("/legacyDate", sharedDate)));

        assertThatCode(() -> validator().validate(valid)).doesNotThrowAnyException();

        GenerationSpec unknown = spec(Map.of(), collection("items", 1,
                Map.of("/date", new DateTime(new SharedDateRef("missing"), DateOutput.BSON_DATE,
                        null, "UTC", "ROOT", REQUIRED))));
        assertThatThrownBy(() -> validator().validate(unknown))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown shared date missing");
    }

    private GenerationSpecValidator validator() { return new GenerationSpecValidator(); }
    private GenerationSpec spec(CollectionGenerationSpec... values) { return spec(Map.of(), values); }
    private GenerationSpec spec(Map<String, SharedDateDefinition> sharedDates, CollectionGenerationSpec... values) {
        return new GenerationSpec(1, 1L, TemplateSelection.SHUFFLED_CYCLE, 10, 2, 10, 5,
                sharedDates, List.of(values), "hash");
    }
    private CollectionGenerationSpec collection(String name,long count,Map<String,GenerationRule> fields){return new CollectionGenerationSpec(name,count,fields);}
}
