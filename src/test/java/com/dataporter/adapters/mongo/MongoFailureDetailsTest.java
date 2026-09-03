package com.dataporter.adapters.mongo;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MongoFailureDetailsTest {
    @Test
    void exposesOnlySafeServerClassification() {
        var response = new BsonDocument("ok", new BsonInt32(0))
                .append("code", new BsonInt32(85))
                .append("codeName", new BsonString("IndexOptionsConflict"))
                .append("errmsg", new BsonString("duplicate key contains customer-secret-value"));

        String details = MongoFailureDetails.classification(
                new IllegalStateException("wrapper", new MongoCommandException(response, new ServerAddress())));

        assertThat(details).isEqualTo(" [MongoDB code=85, codeName=IndexOptionsConflict]")
                .doesNotContain("customer-secret-value", "duplicate key");
    }

    @Test
    void duplicateKeyClassificationKeepsIndexButDropsValues() {
        var error = new MongoWriteException(new WriteError(11000,
                "E11000 duplicate key error collection: db.customers index: target_email_unique dup key: { email: secret@example.test }",
                new BsonDocument()), new ServerAddress("localhost"), Set.of());

        String details = MongoFailureDetails.classification(error);

        assertThat(details).contains("code=11000", "index=target_email_unique")
                .doesNotContain("secret@example.test", "dup key");
    }

    @Test
    void bulkWriteFailureKeepsCodeAndIndexButDropsValues() {
        var error = new MongoBulkWriteException(
                BulkWriteResult.acknowledged(0, 0, 0, null, List.of(), List.of()),
                List.of(new BulkWriteError(11000,
                        "E11000 duplicate key error collection: db.customers index: target_email_unique dup key: { email: secret@example.test }",
                        new BsonDocument(), 0)),
                null, new ServerAddress("localhost"), Set.of());

        String details = MongoFailureDetails.classification(error);

        assertThat(details).contains("code=11000", "index=target_email_unique")
                .doesNotContain("secret@example.test", "dup key");
    }
}
