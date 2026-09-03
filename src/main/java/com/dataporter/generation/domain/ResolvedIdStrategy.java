package com.dataporter.generation.domain;

public record ResolvedIdStrategy(Kind kind, String detail, long numericStart) {
    public enum Kind { EXPLICIT, FIELD_REFERENCE, DETERMINISTIC_OBJECT_ID, DETERMINISTIC_UUID, NUMERIC_SEQUENCE }
    public ResolvedIdStrategy {
        if (kind == null) throw new IllegalArgumentException("id strategy kind is required");
        detail = detail == null ? "" : detail;
    }
    public static ResolvedIdStrategy explicit() { return new ResolvedIdStrategy(Kind.EXPLICIT, "/_id", 0); }
}
