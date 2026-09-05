package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.error.GenerationException;

import org.bson.*;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** JSON Pointer get/set/remove/retain over BSON documents; pointer tokenization is cached per pointer string. */
final class BsonPointerOperations {
    // Pointers are fixed strings from the spec; tokenizing them per set/get/remove call is pure overhead.
    private final Map<String, List<String>> pointerTokensCache = new ConcurrentHashMap<>();

    BsonValue get(BsonDocument document, String pointer) {
        BsonValue value = document;
        for (String token : tokens(pointer)) {
            if (value.isDocument()) value = value.asDocument().get(token);
            else if (value.isArray()) {
                int index = arrayIndex(token, value.asArray().size());
                if (index < 0) return null;
                value = value.asArray().get(index);
            } else return null;
            if (value == null) return null;
        }
        return value;
    }
    void set(BsonDocument document, String pointer, BsonValue value) {
        List<String> tokens = tokens(pointer); BsonValue parent = document;
        for (int i = 0; i < tokens.size() - 1; i++) {
            String token = tokens.get(i); BsonValue next;
            if (parent.isDocument()) {
                next = parent.asDocument().get(token);
                if (next == null || (!next.isDocument() && !next.isArray())) {
                    BsonDocument created = new BsonDocument(); parent.asDocument().put(token, created); next = created;
                }
            } else if (parent.isArray()) {
                int index = requiredArrayIndex(token, parent.asArray().size(), pointer);
                next = parent.asArray().get(index);
                if (!next.isDocument() && !next.isArray()) { BsonDocument created = new BsonDocument(); parent.asArray().set(index, created); next = created; }
            } else throw new GenerationException("Cannot traverse JSON Pointer " + pointer);
            parent = next;
        }
        if (parent.isDocument()) parent.asDocument().put(tokens.getLast(), value);
        else if (parent.isArray()) parent.asArray().set(requiredArrayIndex(tokens.getLast(), parent.asArray().size(), pointer), value);
        else throw new GenerationException("Cannot set JSON Pointer " + pointer);
    }
    void remove(BsonDocument document, String pointer) {
        List<String> tokens = tokens(pointer); BsonValue parent = document;
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (parent.isDocument()) parent = parent.asDocument().get(tokens.get(i));
            else if (parent.isArray()) { int index=arrayIndex(tokens.get(i),parent.asArray().size()); if(index<0)return; parent=parent.asArray().get(index); }
            else return;
            if (parent == null) return;
        }
        if (parent.isDocument()) parent.asDocument().remove(tokens.getLast());
        else if (parent.isArray()) { int index=arrayIndex(tokens.getLast(),parent.asArray().size()); if(index>=0)parent.asArray().remove(index); }
    }
    /** Copies the document keeping only paths in {@code keep} (exact pointer or container of one);
     * arrays are kept whole because partial retention would retype them and corrupt set/get.
     * Kept subtrees are aliased, not cloned: callers pass the freshly decoded mutable document
     * and discard it afterwards. */
    BsonDocument retain(BsonDocument document, Set<String> keep) {
        BsonDocument result = new BsonDocument();
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            String path = "/" + escape(entry.getKey());
            if (!kept(path, keep)) continue;
            if (entry.getValue().isDocument() && !keep.contains(path))
                result.put(entry.getKey(), retain(entry.getValue().asDocument(), keep, path));
            else result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
    private BsonDocument retain(BsonDocument document, Set<String> keep, String prefix) {
        BsonDocument result = new BsonDocument();
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            String path = prefix + "/" + escape(entry.getKey());
            if (!kept(path, keep)) continue;
            if (entry.getValue().isDocument() && !keep.contains(path))
                result.put(entry.getKey(), retain(entry.getValue().asDocument(), keep, path));
            else result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
    /** Copies the document replacing every unkept value with the default of its BSON type:
     * documents keep their keys with defaulted leaves, unkept arrays become empty, arrays
     * containing a kept path stay whole (mirroring retain). Kept subtrees are aliased, not cloned. */
    BsonDocument blank(BsonDocument document, Set<String> keep) { return blank(document, keep, ""); }
    private BsonDocument blank(BsonDocument document, Set<String> keep, String prefix) {
        BsonDocument result = new BsonDocument();
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            String path = prefix + "/" + escape(entry.getKey());
            if (entry.getValue().isDocument() && !keep.contains(path))
                result.put(entry.getKey(), blank(entry.getValue().asDocument(), keep, path));
            else if (kept(path, keep)) result.put(entry.getKey(), entry.getValue());
            else result.put(entry.getKey(), defaultValue(entry.getValue()));
        }
        return result;
    }
    static BsonValue defaultValue(BsonValue value) {
        if (value.isArray()) return new BsonArray();
        if (value.isString()) return new BsonString("");
        if (value.isBoolean()) return BsonBoolean.FALSE;
        if (value.isInt32()) return new BsonInt32(0);
        if (value.isInt64()) return new BsonInt64(0);
        if (value.isDouble()) return new BsonDouble(0.0);
        if (value.isDecimal128()) return new BsonDecimal128(new Decimal128(java.math.BigDecimal.ZERO));
        if (value.isDateTime()) return new BsonDateTime(0);
        if (value.isTimestamp()) return new BsonTimestamp(0);
        if (value.isObjectId()) return new BsonObjectId(new ObjectId(new byte[12]));
        if (value.isBinary()) return new BsonBinary(new byte[0]);
        if (value.isSymbol()) return new BsonSymbol("");
        if (value.isRegularExpression()) return new BsonRegularExpression("");
        if (value.isJavaScript()) return new BsonJavaScript("");
        if (value.isJavaScriptWithScope()) return new BsonJavaScriptWithScope("", new BsonDocument());
        if (value.isNull() || value instanceof BsonMinKey || value instanceof BsonMaxKey) return value;
        return BsonNull.VALUE;
    }
    static boolean kept(String path, Set<String> keep) {
        if (keep.contains(path)) return true;
        String prefix = path + "/";
        return keep.stream().anyMatch(entry -> entry.startsWith(prefix));
    }
    List<String> tokens(String pointer) {
        List<String> cached = pointerTokensCache.get(pointer);
        if (cached != null) return cached;
        List<String> computed = Arrays.stream(pointer.substring(1).split("/", -1))
                .map(v -> v.replace("~1", "/").replace("~0", "~")).toList();
        pointerTokensCache.put(pointer, computed);
        return computed;
    }
    static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private static int arrayIndex(String token,int size){try{int value=Integer.parseInt(token);return value>=0&&value<size?value:-1;}catch(NumberFormatException e){return -1;}}
    private static int requiredArrayIndex(String token,int size,String pointer){int value=arrayIndex(token,size);if(value<0)throw new GenerationException("Invalid array index in JSON Pointer "+pointer);return value;}
}
