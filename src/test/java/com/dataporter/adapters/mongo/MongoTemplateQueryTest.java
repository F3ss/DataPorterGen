package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.TemplateQuery;

import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MongoTemplateQueryTest {
    @Test void convertsTheImmutableYamlValueTreeToExactBsonTypes() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("text", "A");
        values.put("small", 7);
        values.put("large", 2_147_483_648L);
        values.put("decimal", new BigDecimal("1.50"));
        values.put("nullable", null);
        values.put("nested", Map.of("$in", List.of("A", "B")));

        BsonDocument actual = MongoTemplateQuery.toBson(new TemplateQuery(values));

        assertThat(actual.get("text")).isEqualTo(new BsonString("A"));
        assertThat(actual.get("small")).isEqualTo(new BsonInt32(7));
        assertThat(actual.get("large")).isEqualTo(new BsonInt64(2_147_483_648L));
        assertThat(actual.get("decimal")).isEqualTo(new BsonDecimal128(new Decimal128(new BigDecimal("1.50"))));
        assertThat(actual.get("nullable")).isEqualTo(BsonNull.VALUE);
        assertThat(actual.getDocument("nested").getArray("$in").getValues())
                .containsExactly(new BsonString("A"), new BsonString("B"));
    }

    @Test void queryDefensivelyCopiesNestedMutableValues() {
        ArrayList<Object> values = new ArrayList<>();
        values.add("A");
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("field", Map.of("$in", values));

        TemplateQuery query = new TemplateQuery(source);
        values.add("B");
        source.put("other", true);

        BsonDocument actual = MongoTemplateQuery.toBson(query);
        assertThat(actual.keySet()).containsExactly("field");
        assertThat(actual.getDocument("field").getArray("$in").getValues())
                .containsExactly(new BsonString("A"));
    }
}
