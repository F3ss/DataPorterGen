package com.dataporter.migration.application;

import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.error.SourceInspectionException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ViewOrdererTest {
    private final ViewOrderer orderer = new ViewOrderer();

    @Test
    void ordersDependentViewsAfterTheirInputs() {
        var child = new ViewDefinition("premium", "active", BsonPayload.emptyArray());
        var parent = new ViewDefinition("active", "customers", BsonPayload.emptyArray());

        assertThat(orderer.order(List.of(child, parent))).extracting(ViewDefinition::name)
                .containsExactly("active", "premium");
    }

    @Test
    void detectsCycles() {
        var a = new ViewDefinition("a", "b", BsonPayload.emptyArray());
        var b = new ViewDefinition("b", "a", BsonPayload.emptyArray());

        assertThatThrownBy(() -> orderer.order(List.of(a, b)))
                .isInstanceOf(SourceInspectionException.class)
                .hasMessageContaining("Cyclic view dependency");
    }
}
