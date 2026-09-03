package com.dataporter.adapters.mongo;

import com.dataporter.migration.domain.error.DocumentMigrationException;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;

import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;

import java.util.ArrayList;
import java.util.List;

/** Exact-_id replace-with-upsert write models shared by MERGE and GENERATE target writes. */
final class MongoWriteModels {
    private MongoWriteModels() { }

    static List<WriteModel<RawBsonDocument>> replaceUpsertModels(DataBatch batch) {
        List<WriteModel<RawBsonDocument>> replacements = new ArrayList<>(batch.documents().size());
        for (BsonPayload payload : batch.documents()) {
            RawBsonDocument document = MongoBson.decode(payload);
            replacements.add(new ReplaceOneModel<>(new BsonDocument("_id", requiredId(document, batch.collection())),
                    document, new ReplaceOptions().upsert(true)));
        }
        return replacements;
    }

    private static BsonValue requiredId(BsonDocument document, String collection) {
        BsonValue id = document.get("_id");
        if (id == null) throw new DocumentMigrationException("Source document has no _id in " + collection,
                new IllegalArgumentException("missing _id"));
        return id;
    }
}
