package com.dataporter.migration.application;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.CollectionSelection;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.domain.DatabaseObjectType;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.error.ConfigurationException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MigrationPlanSelectorTest {
    private final MigrationPlanSelector selector = new MigrationPlanSelector();

    @Test
    void emptySelectionKeepsEntirePlan() {
        var result = selector.select(plan(), CollectionSelection.all());

        assertThat(result.plan().collections()).extracting(CollectionDefinition::name)
                .containsExactly("customers", "events", "orders");
        assertThat(result.plan().indexes()).hasSize(3);
        assertThat(result.plan().views()).extracting(ViewDefinition::name)
                .containsExactly("active_customers", "premium_customers");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void includeKeepsOnlyNamedCollectionsTheirIndexesAndDependentViews() {
        var selection = CollectionSelection.from(List.of(" customers ", "customers"), List.of());

        var result = selector.select(plan(), selection);

        assertThat(result.plan().collections()).extracting(CollectionDefinition::name).containsExactly("customers");
        assertThat(result.plan().indexes()).extracting(IndexDefinition::collection).containsExactly("customers");
        assertThat(result.plan().views()).extracting(ViewDefinition::name)
                .containsExactly("active_customers", "premium_customers");
        assertThat(result.skipped()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("events");
            assertThat(item.status()).isEqualTo(ObjectStatus.SKIPPED);
        });
    }

    @Test
    void excludeRemovesCollectionsIndexesAndViewsDependingOnThem() {
        var result = selector.select(plan(), CollectionSelection.from(List.of(), List.of("customers")));

        assertThat(result.plan().collections()).extracting(CollectionDefinition::name)
                .containsExactly("events", "orders");
        assertThat(result.plan().indexes()).extracting(IndexDefinition::collection)
                .containsExactly("events", "orders");
        assertThat(result.plan().views()).isEmpty();
        assertThat(result.skipped()).filteredOn(item -> item.type() == DatabaseObjectType.VIEW).hasSize(2);
    }

    @Test
    void exactNamesAreCaseSensitive() {
        assertThatThrownBy(() -> selector.select(plan(),
                CollectionSelection.from(List.of("Customers"), List.of())))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Customers");
    }

    @Test
    void unknownNamesFailWithAllMissingNames() {
        assertThatThrownBy(() -> selector.select(plan(),
                CollectionSelection.from(List.of("missing_b", "missing_a"), List.of())))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("include-collections")
                .hasMessageContaining("missing_a", "missing_b");
    }

    @Test
    void excludingEveryCollectionProducesSuccessfulEmptyPlanDescription() {
        var result = selector.select(plan(),
                CollectionSelection.from(List.of(), List.of("customers", "events", "orders")));

        assertThat(result.plan().collections()).isEmpty();
        assertThat(result.plan().indexes()).isEmpty();
        assertThat(result.plan().views()).isEmpty();
        assertThat(result.skipped()).hasSize(8);
    }

    private MigrationPlan plan() {
        BsonPayload empty = BsonPayload.emptyArray();
        return new MigrationPlan(
                List.of(new CollectionDefinition("customers", empty), new CollectionDefinition("events", empty),
                        new CollectionDefinition("orders", empty)),
                List.of(new IndexDefinition("customers", "customers_idx", empty),
                        new IndexDefinition("events", "events_idx", empty),
                        new IndexDefinition("orders", "orders_idx", empty)),
                List.of(new ViewDefinition("active_customers", "customers", empty),
                        new ViewDefinition("premium_customers", "active_customers", empty)));
    }
}
