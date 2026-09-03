package com.dataporter.adapters.mongo;

import com.dataporter.migration.ports.out.TransientFailureClassifier;

import com.mongodb.*;

public final class MongoTransientFailureClassifier implements TransientFailureClassifier {
    public boolean isTransient(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoSecurityException || cause instanceof MongoCommandException command
                    && (command.getErrorCode() == 13 || command.getErrorCode() == 18)) return false;
            if (cause instanceof MongoSocketException || cause instanceof MongoTimeoutException
                    || cause instanceof MongoNotPrimaryException || cause instanceof MongoNodeIsRecoveringException)
                return true;
            if (cause instanceof MongoException mongo && (mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                    || mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))) return true;
        }
        return false;
    }
}
