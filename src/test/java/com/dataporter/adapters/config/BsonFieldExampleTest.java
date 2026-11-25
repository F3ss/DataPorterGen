package com.dataporter.adapters.config;

import org.bson.BsonBinarySubType;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BsonFieldExampleTest {
    @Test void rendersSingleLineExamplesWithTheirExpectedBsonTypes() {
        for (BsonType type : EnumSet.complementOf(EnumSet.of(BsonType.END_OF_DOCUMENT))) {
            String example = BsonFieldExample.render("items", "/field", List.of(type.name()));
            assertThat(example).doesNotContain("\n", "\r");
            assertThat(parse(example).getBsonType()).as("example for %s", type).isEqualTo(type);
        }
        String uuid = BsonFieldExample.render("items", "/uuid", List.of("UUID"));
        assertThat(parse(uuid).asBinary().getType()).isEqualTo(BsonBinarySubType.UUID_STANDARD.getValue());
        assertThat(BsonFieldExample.render("items", "/unknown", List.of("UNKNOWN"))).isEqualTo("null");
    }

    @Test void prefersAKnownNonNullTypeAndKeepsExamplesStable() {
        List<String> types = List.of("INT64", "NULL", "STRING");
        String example = BsonFieldExample.render("items", "/mixed", types);
        assertThat(parse(example).getBsonType()).isIn(BsonType.INT64, BsonType.STRING);
        assertThat(BsonFieldExample.render("items", "/mixed", types)).isEqualTo(example);
    }

    private static BsonValue parse(String example) {
        return BsonDocument.parse("{\"value\":" + example + "}").get("value");
    }
}
