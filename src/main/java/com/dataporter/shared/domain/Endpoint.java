package com.dataporter.shared.domain;

import com.dataporter.shared.security.SecretMasker;

import java.util.Objects;

public record Endpoint(String uri, String database) {
    public Endpoint {
        uri = Objects.requireNonNullElse(uri, "").trim();
        database = Objects.requireNonNullElse(database, "").trim();
    }

    public String safeUri() { return SecretMasker.sanitize(uri); }
}
