package com.dataporter.adapters.mongo;

import org.bson.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoMetadataTest {
    @Test
    void normalizesCollectionAndCollationServerDefaults() {
        BsonDocument source = new BsonDocument("validator", new BsonDocument("active", new BsonInt32(1)));
        BsonDocument target = source.clone()
                .append("validationLevel", new BsonString("strict"))
                .append("validationAction", new BsonString("error"))
                .append("capped", BsonBoolean.FALSE)
                .append("collation", new BsonDocument("locale", new BsonString("simple")));

        assertThat(MongoMetadata.collectionEquivalent(MongoBson.encode(source), MongoBson.encode(target))).isTrue();
    }

    @Test
    void indexComparisonPreservesCompoundKeyOrderAndIgnoresDefaults() {
        BsonDocument source = new BsonDocument("name", new BsonString("compound"))
                .append("key", new BsonDocument("a", new BsonInt32(1)).append("b", new BsonInt32(-1)));
        BsonDocument equivalent = source.clone().append("sparse", BsonBoolean.FALSE);
        BsonDocument reordered = new BsonDocument("name", new BsonString("compound"))
                .append("key", new BsonDocument("b", new BsonInt32(-1)).append("a", new BsonInt32(1)));

        assertThat(MongoMetadata.indexEquivalent(MongoBson.encode(source), MongoBson.encode(equivalent))).isTrue();
        assertThat(MongoMetadata.indexEquivalent(MongoBson.encode(source), MongoBson.encode(reordered))).isFalse();
    }

    @Test
    void viewComparisonTreatsMissingAndEmptyPipelineAsEquivalent() {
        BsonDocument source = new BsonDocument("viewOn", new BsonString("customers"));
        BsonDocument target = source.clone().append("pipeline", new BsonArray());

        assertThat(MongoMetadata.viewEquivalent(MongoBson.encode(source), MongoBson.encode(target))).isTrue();
    }
}
