package com.dataporter.adapters.mongo;

import com.dataporter.generation.ports.out.GenerationSchemaSource.ScanCoverage;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MongoFieldSchemaTest {
    @Test void combinesDeclaredAndObservedPathsAndTypesWithoutTreatingNullAsAbsent() {
        var schema = new MongoFieldSchema(Set.of("/declared", "/nullable", "/items/0/code", "/items/1/actual",
                "/items/9/notDeclared", "/absent"));
        schema.readValidator(BsonDocument.parse("""
                { "$and": [{ "$jsonSchema": { "properties": {
                  "declared": { "bsonType": ["string", "null"] },
                  "unknownType": {},
                  "amount": { "bsonType": "long" },
                  "items": { "bsonType": "array", "items": { "properties": { "code": { "bsonType": "int" } } } }
                } } }] }
                """));
        schema.observe(BsonDocument.parse("""
                { "_id": 1, "nullable": null, "amount": 1.5, "a/b~c": { "value": true },
                  "items": [null, { "actual": "x" }] }
                """));
        var result = schema.result(ScanCoverage.COMPLETE);

        assertThat(result.presentPaths()).containsExactlyInAnyOrder("/declared", "/nullable", "/items/0/code", "/items/1/actual");
        assertThat(result.fields()).containsEntry("/unknownType", Set.of("UNKNOWN"))
                .containsEntry("/declared", Set.of("STRING", "NULL"))
                .containsEntry("/nullable", Set.of("NULL"))
                .containsEntry("/amount", Set.of("INT64", "DOUBLE"))
                .containsEntry("/a~1b~0c/value", Set.of("BOOLEAN"))
                .containsEntry("/items", Set.of("ARRAY"))
                .doesNotContainKey("/items/1/actual");
    }
}
