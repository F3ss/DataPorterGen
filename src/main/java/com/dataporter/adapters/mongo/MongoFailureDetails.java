package com.dataporter.adapters.mongo;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoException;
import com.mongodb.MongoServerException;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteError;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MongoFailureDetails {
    private static final Pattern DUPLICATE_INDEX = Pattern.compile(
            "\\bindex:\\s+([A-Za-z0-9_.-]{1,128})\\s+dup key\\b", Pattern.CASE_INSENSITIVE);
    private MongoFailureDetails() {}

    static String classification(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof MongoWriteException write) {
                return fields(write.getCode(), write.getErrorCodeName(), safeDuplicateIndex(write.getError()));
            }
            if (current instanceof MongoBulkWriteException bulk && !bulk.getWriteErrors().isEmpty()) {
                WriteError first = bulk.getWriteErrors().get(0);
                return fields(first.getCode(), null, safeDuplicateIndex(first));
            }
            if (current instanceof MongoServerException server) {
                return fields(server.getCode(), server.getErrorCodeName(), null);
            }
            if (current instanceof MongoException mongo) {
                return fields(mongo.getCode(), null, null);
            }
        }
        return "";
    }

    private static String safeDuplicateIndex(WriteError error) {
        Matcher matcher = DUPLICATE_INDEX.matcher(error.getMessage());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String fields(int code, String codeName, String index) {
        if (code == 0 && (codeName == null || codeName.isBlank()) && index == null) return "";
        StringBuilder result = new StringBuilder(" [MongoDB");
        if (code != 0) result.append(" code=").append(code);
        if (codeName != null && !codeName.isBlank()) result.append(", codeName=").append(codeName);
        if (index != null) result.append(", index=").append(index);
        return result.append(']').toString();
    }
}
