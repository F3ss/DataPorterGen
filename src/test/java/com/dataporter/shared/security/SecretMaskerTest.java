package com.dataporter.shared.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskerTest {
    @Test
    void removesCredentialsAndEntireQueryString() {
        String safe = SecretMasker.sanitize(
                "mongodb://alice:s3cr3t@db:27017/catalog?authSource=admin&tlsCertificateKeyFile=/secret/key.pem"
                        + "&authMechanismProperties=SERVICE_NAME:krb5&proxyPassword=pp&futureSecret=zzz");
        assertThat(safe).isEqualTo("mongodb://db:27017/catalog");
    }

    @Test
    void removesFragment() {
        assertThat(SecretMasker.sanitize("mongodb://db:27017/catalog#frag")).isEqualTo("mongodb://db:27017/catalog");
    }

    @Test
    void redactsCredentialsEmbeddedInArbitraryMessages() {
        String safe = SecretMasker.redact("Cannot connect to mongodb://user:password@host:27017/db");
        assertThat(safe).doesNotContain("user", "password").contains("mongodb://host:27017/db");
    }

    @Test
    void redactStripsQueryFromEmbeddedUris() {
        String safe = SecretMasker.redact("Failed after mongodb://u:p@host:27017/db?authMechanismProperties=SERVICE_NAME:krb5&x=1, retrying");
        assertThat(safe).doesNotContain("SERVICE_NAME", "krb5", "u:p", "x=1").contains("mongodb://host:27017/db");
    }
}
