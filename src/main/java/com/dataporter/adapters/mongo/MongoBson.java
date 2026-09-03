package com.dataporter.adapters.mongo;

import com.dataporter.shared.bson.BsonPayload;

import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;

import java.util.Arrays;

final class MongoBson {
    private static final BsonDocumentCodec CODEC = new BsonDocumentCodec();
    private MongoBson() {}

    static BsonPayload encode(BsonDocument document) {
        RawBsonDocument raw = new RawBsonDocument(document, CODEC);
        return slice(raw.getBackingArray(), raw.getByteOffset(), raw.getByteLength());
    }

    static RawBsonDocument decode(BsonPayload payload) { return new RawBsonDocument(payload.bytes()); }

    static BsonDocument decodeMutable(BsonPayload payload) { return new RawBsonDocument(payload.bytes()).decode(CODEC); }

    static BsonPayload encode(RawBsonDocument document) {
        return slice(document.getBackingArray(), document.getByteOffset(), document.getByteLength());
    }

    // BsonPayload clones defensively, so the exact-size backing array needs no intermediate copy.
    private static BsonPayload slice(byte[] backing, int offset, int length) {
        return offset == 0 && length == backing.length
                ? new BsonPayload(backing)
                : new BsonPayload(Arrays.copyOfRange(backing, offset, offset + length));
    }
}
