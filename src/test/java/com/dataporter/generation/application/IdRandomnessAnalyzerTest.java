package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationRule.*;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdRandomnessAnalyzerTest {
    private final IdRandomnessAnalyzer analyzer = new IdRandomnessAnalyzer();

    @Test void findsDirectNestedAndLocalRefRandomSourcesAndDeduplicatesRepeatedRefs() {
        RandomString random = random(6);
        LinkedHashMap<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/generated", new Concat(List.of(
                new Literal("prefix", RuleOptions.REQUIRED), random), RuleOptions.REQUIRED));
        fields.put("/_id", new Concat(List.of(
                new Ref(null, "/generated", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Literal("|", RuleOptions.REQUIRED),
                new Ref(null, "/generated", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                random(6)), RuleOptions.REQUIRED));
        CollectionGenerationSpec items = new CollectionGenerationSpec("items", 300_000, fields);

        IdRandomnessAnalyzer.Analysis analysis = analyzer.analyze(items, Map.of("items", items));

        assertThat(analysis.sources()).hasSize(2);
        assertThat(analysis.warning("items", items.count()))
                .contains("randomSources=2", "keyspace=3226266762397899821056", "risk=1.395e-11");
    }

    @Test void combinesIndependentCrossCollectionAndLocalRandomSourcesWithoutDependingOnConcatOrder() {
        CollectionGenerationSpec parent = new CollectionGenerationSpec("parents", 300_000,
                Map.of("/_id", random(6)));
        LinkedHashMap<String,GenerationRule> childFields = new LinkedHashMap<>();
        childFields.put("/local", random(6));
        childFields.put("/fixed", new Literal("fixed", RuleOptions.REQUIRED));
        childFields.put("/_id", new Concat(List.of(
                new Literal("prefix|", RuleOptions.REQUIRED),
                new Ref(null, "/fixed", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Ref("parents", "/_id", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Ref(null, "/templateValue", MissingPolicy.ERROR, RuleOptions.REQUIRED),
                new Ref(null, "/local", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED));
        CollectionGenerationSpec child = new CollectionGenerationSpec("children", 300_000, childFields);
        Map<String,CollectionGenerationSpec> ordered = new LinkedHashMap<>();
        ordered.put(parent.name(), parent);
        ordered.put(child.name(), child);

        IdRandomnessAnalyzer.Analysis one = analyzer.analyze(parent, ordered);
        IdRandomnessAnalyzer.Analysis two = analyzer.analyze(child, ordered);

        assertThat(one.warning("parents", parent.count()))
                .contains("randomSources=1", "keyspace=56800235584", "risk=54.72%");
        assertThat(two.warning("children", child.count()))
                .contains("randomSources=2", "keyspace=3226266762397899821056", "risk=1.395e-11");
    }

    @Test void reportsUnknownForVariableLengthRandomSource() {
        CollectionGenerationSpec items = new CollectionGenerationSpec("items", 10,
                Map.of("/_id", new RandomString(Alphabet.ALPHANUMERIC, null, 2, 6, RuleOptions.REQUIRED)));

        assertThat(analyzer.analyze(items, Map.of("items", items)).warning("items", 10))
                .contains("keyspace=unknown", "risk=unknown");
    }

    @Test void usesExclusiveRangeAsRandomAlphaNumKeyspaceThroughConcatAndRef() {
        RandomAlphaNumStringBetween random = new RandomAlphaNumStringBetween(
                BigInteger.valueOf(2_000_000), BigInteger.valueOf(2_000_002), 6, RuleOptions.REQUIRED);
        LinkedHashMap<String,GenerationRule> fields = new LinkedHashMap<>();
        fields.put("/code", random);
        fields.put("/_id", new Concat(List.of(new Literal("ID-", RuleOptions.REQUIRED),
                new Ref(null, "/code", MissingPolicy.ERROR, RuleOptions.REQUIRED)), RuleOptions.REQUIRED));
        CollectionGenerationSpec items = new CollectionGenerationSpec("items", 3, fields);

        IdRandomnessAnalyzer.Analysis analysis = analyzer.analyze(items, Map.of("items", items));

        assertThat(analysis.sources()).hasSize(1);
        assertThat(analysis.warning("items", items.count()))
                .contains("randomSources=1", "keyspace=2", "risk=guaranteed");
    }

    @Test void requiresRandomnessInEveryWeightedBranchAndReportsUnknownRisk() {
        GenerationRule randomChoice = new WeightedChoice(List.of(
                new Choice(new RandomAlphaNumStringBetween(BigInteger.ZERO, BigInteger.TWO, 2,
                        RuleOptions.REQUIRED), 1),
                new Choice(random(2), 1)), RuleOptions.REQUIRED);
        GenerationRule partlyStatic = new WeightedChoice(List.of(
                new Choice(new Literal("fixed", RuleOptions.REQUIRED), 1),
                new Choice(random(2), 1)), RuleOptions.REQUIRED);
        CollectionGenerationSpec accepted = new CollectionGenerationSpec("accepted", 10,
                Map.of("/_id", randomChoice));
        CollectionGenerationSpec rejected = new CollectionGenerationSpec("rejected", 10,
                Map.of("/_id", partlyStatic));

        IdRandomnessAnalyzer.Analysis acceptedAnalysis = analyzer.analyze(accepted, Map.of("accepted", accepted));
        IdRandomnessAnalyzer.Analysis rejectedAnalysis = analyzer.analyze(rejected, Map.of("rejected", rejected));

        assertThat(acceptedAnalysis.hasGuaranteedRandom()).isTrue();
        assertThat(acceptedAnalysis.warning("accepted", accepted.count()))
                .contains("keyspace=unknown", "risk=unknown");
        assertThat(rejectedAnalysis.hasGuaranteedRandom()).isFalse();
    }

    private RandomString random(int length) {
        return new RandomString(Alphabet.ALPHANUMERIC, null, length, length, RuleOptions.REQUIRED);
    }
}
