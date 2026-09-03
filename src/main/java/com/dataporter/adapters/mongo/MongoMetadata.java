package com.dataporter.adapters.mongo;

import com.dataporter.shared.bson.BsonPayload;

import org.bson.*;

import java.util.ArrayList;

final class MongoMetadata {
    private MongoMetadata() {}

    static boolean collectionEquivalent(BsonPayload source, BsonPayload target) {
        return normalizeCollection(MongoBson.decodeMutable(source))
                .equals(normalizeCollection(MongoBson.decodeMutable(target)));
    }

    static boolean indexEquivalent(BsonPayload source, BsonPayload target) {
        BsonDocument left = normalizeIndex(MongoBson.decodeMutable(source), false);
        BsonDocument right = normalizeIndex(MongoBson.decodeMutable(target), false);
        return left.equals(right) && orderedKeysEqual(left, right);
    }

    static boolean sameIndexKey(BsonPayload source, BsonPayload target) {
        BsonDocument left = MongoBson.decode(source);
        BsonDocument right = MongoBson.decode(target);
        return orderedKeysEqual(left, right);
    }

    static boolean viewEquivalent(BsonPayload source, BsonPayload target) {
        return normalizeView(MongoBson.decodeMutable(source))
                .equals(normalizeView(MongoBson.decodeMutable(target)));
    }

    private static BsonDocument normalizeCollection(BsonDocument options) {
        options.remove("uuid");
        options.remove("idIndex");
        removeBooleanDefault(options, "capped", false);
        removeStringDefault(options, "validationLevel", "strict");
        removeStringDefault(options, "validationAction", "error");
        BsonDocument validator = options.getDocument("validator", null);
        if (validator != null && validator.isEmpty()) options.remove("validator");
        normalizeCollation(options);
        return options;
    }

    private static BsonDocument normalizeIndex(BsonDocument index, boolean removeName) {
        index.remove("v");
        index.remove("ns");
        if (removeName) index.remove("name");
        removeBooleanDefault(index, "unique", false);
        removeBooleanDefault(index, "sparse", false);
        removeBooleanDefault(index, "hidden", false);
        normalizeCollation(index);
        return index;
    }

    private static BsonDocument normalizeView(BsonDocument options) {
        BsonArray pipeline = options.getArray("pipeline", null);
        if (pipeline != null && pipeline.isEmpty()) options.remove("pipeline");
        normalizeCollation(options);
        return options;
    }

    private static void normalizeCollation(BsonDocument owner) {
        BsonDocument collation = owner.getDocument("collation", null);
        if (collation == null) return;
        collation.remove("version");
        removeBooleanDefault(collation, "caseLevel", false);
        removeStringDefault(collation, "caseFirst", "off");
        removeIntDefault(collation, "strength", 3);
        removeBooleanDefault(collation, "numericOrdering", false);
        removeStringDefault(collation, "alternate", "non-ignorable");
        removeStringDefault(collation, "maxVariable", "punct");
        removeBooleanDefault(collation, "normalization", false);
        removeBooleanDefault(collation, "backwards", false);
        if ("simple".equals(collation.getString("locale", new BsonString("")).getValue())
                && collation.size() == 1) owner.remove("collation");
    }

    private static boolean orderedKeysEqual(BsonDocument left, BsonDocument right) {
        BsonDocument leftKey = left.getDocument("key", null);
        BsonDocument rightKey = right.getDocument("key", null);
        return leftKey != null && rightKey != null
                && new ArrayList<>(leftKey.entrySet()).equals(new ArrayList<>(rightKey.entrySet()));
    }

    private static void removeBooleanDefault(BsonDocument document, String field, boolean defaultValue) {
        BsonValue value = document.get(field);
        if (value != null && value.isBoolean() && value.asBoolean().getValue() == defaultValue) document.remove(field);
    }

    private static void removeStringDefault(BsonDocument document, String field, String defaultValue) {
        BsonValue value = document.get(field);
        if (value != null && value.isString() && defaultValue.equals(value.asString().getValue())) document.remove(field);
    }

    private static void removeIntDefault(BsonDocument document, String field, int defaultValue) {
        BsonValue value = document.get(field);
        if (value != null && value.isNumber() && value.asNumber().intValue() == defaultValue) document.remove(field);
    }
}
