package com.dataporter.migration.domain;

import com.dataporter.shared.domain.DatabaseObjectType;
import com.dataporter.shared.domain.ObjectStatus;

public record ObjectResult(String name, DatabaseObjectType type, ObjectStatus status,
                           long documents, long bytes, String detail,
                           long sourceDocuments, long insertedDocuments, long replacedDocuments,
                           long conflicts) {
    public ObjectResult(String name, DatabaseObjectType type, ObjectStatus status,
                        long documents, long bytes, String detail) {
        this(name, type, status, documents, bytes, detail, documents, documents, 0, 0);
    }

    public static ObjectResult mergeCollection(String name, ObjectStatus status, long sourceDocuments,
                                               long bytes, long inserted, long replaced,
                                               long conflicts, String detail) {
        return new ObjectResult(name, DatabaseObjectType.COLLECTION, status, sourceDocuments, bytes, detail,
                sourceDocuments, inserted, replaced, conflicts);
    }
}
