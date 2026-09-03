package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.TemplateFacts;
import com.dataporter.shared.bson.BsonPayload;

import org.bson.BsonBinarySubType;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.LinkedHashSet;
import java.util.Set;

/** Derives template facts (_id kind and paths equal to _id) from a raw BSON template. */
final class BsonTemplateInspector {
    TemplateFacts inspect(BsonPayload template) {
        BsonDocument document = MongoBson.decode(template);
        BsonValue id = document.get("_id");
        TemplateFacts.IdKind kind = idKind(id);
        Set<String> equal = new LinkedHashSet<>();
        if (id != null && scalar(id)) collectEqual(document, "", id, equal);
        equal.remove("/_id");
        return new TemplateFacts(kind, equal);
    }

    private static void collectEqual(BsonDocument document, String base, BsonValue id, Set<String> equal) {
        document.forEach((name, value) -> {
            String path = base + "/" + BsonPointerOperations.escape(name);
            if (value.isDocument()) collectEqual(value.asDocument(), path, id, equal);
            else if (scalar(value) && value.equals(id)) equal.add(path);
        });
    }
    private static boolean scalar(BsonValue value) { return value != null && !value.isArray() && !value.isDocument(); }
    private static TemplateFacts.IdKind idKind(BsonValue id) {
        if (id == null) return TemplateFacts.IdKind.MISSING;
        if (id.isObjectId()) return TemplateFacts.IdKind.OBJECT_ID;
        if (id.isBinary() && id.asBinary().getType() == BsonBinarySubType.UUID_STANDARD.getValue()) return TemplateFacts.IdKind.UUID;
        if (id.isInt32()) return TemplateFacts.IdKind.INT32;
        if (id.isInt64()) return TemplateFacts.IdKind.INT64;
        if (id.isString()) return TemplateFacts.IdKind.STRING;
        return TemplateFacts.IdKind.OTHER;
    }
}
