package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.TemplateQuery;

import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class MongoTemplateQuery {
    private MongoTemplateQuery() { }

    static BsonDocument toBson(TemplateQuery query) {
        return document(query.document());
    }

    private static BsonDocument document(Map<String, Object> values) {
        BsonDocument result = new BsonDocument();
        values.forEach((name, value) -> result.put(name, value(value)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static BsonValue value(Object value) {
        if (value == null) return BsonNull.VALUE;
        if (value instanceof String item) return new BsonString(item);
        if (value instanceof Boolean item) return BsonBoolean.valueOf(item);
        if (value instanceof Integer item) return new BsonInt32(item);
        if (value instanceof Long item) return new BsonInt64(item);
        if (value instanceof BigDecimal item) return new BsonDecimal128(new Decimal128(item));
        if (value instanceof Map<?, ?> item) return document((Map<String, Object>) item);
        if (value instanceof List<?> items) {
            BsonArray result = new BsonArray();
            items.forEach(item -> result.add(value(item)));
            return result;
        }
        throw new IllegalArgumentException("Unsupported generation query value type " + value.getClass().getSimpleName());
    }
}
