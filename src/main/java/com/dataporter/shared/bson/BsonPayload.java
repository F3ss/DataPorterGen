package com.dataporter.shared.bson;

import java.util.Arrays;

/** Opaque BSON bytes keep the domain independent from the MongoDB driver. */
public final class BsonPayload {
    private static final byte[] EMPTY_ARRAY = new byte[]{5, 0, 0, 0, 0};
    private final byte[] bytes;

    public BsonPayload(byte[] bytes) {
        if (bytes == null || bytes.length < 5) throw new IllegalArgumentException("Invalid BSON payload");
        this.bytes = bytes.clone();
    }

    public static BsonPayload emptyArray() { return new BsonPayload(EMPTY_ARRAY); }
    public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }

    @Override public boolean equals(Object other) {
        return other instanceof BsonPayload that && Arrays.equals(bytes, that.bytes);
    }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public String toString() { return "BsonPayload[" + bytes.length + " bytes]"; }
}
