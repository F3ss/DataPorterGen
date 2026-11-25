package com.dataporter.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DataPorterGenApplicationTest {
    @Test
    void helpUsesProductName() {
        assertThat(DataPorterGenApplication.help())
                .startsWith("DataPorterGen (Java 21)")
                .contains("--migration.generation.allow-unproven-ids=false", "GENERATION_ALLOW_UNPROVEN_IDS")
                .contains("--migration.generation.validation-max-documents=10000", "GENERATION_VALIDATION_MAX_DOCUMENTS")
                .doesNotContain("MongoDB database migrator");
    }

    @Test void applicationConfigExposesGenerationAllowUnprovenIdsEnvironmentVariable() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("allow-unproven-ids: ${GENERATION_ALLOW_UNPROVEN_IDS:false}",
                            "validation-max-documents: ${GENERATION_VALIDATION_MAX_DOCUMENTS:10000}");
        }
    }
}
