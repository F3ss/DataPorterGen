package com.dataporter.generation.domain;

import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.security.SecretMasker;

public record GenerationCollectionResult(String name, long requested, long generated, long written,
                                         long snapshotTemplates, long snapshotBytes, boolean snapshotTruncated,
                                         long generatedBytes,
                                         ResolvedIdStrategy idStrategy, ObjectStatus status, String message) {
    public GenerationCollectionResult {
        message = SecretMasker.redact(message == null ? "" : message);
    }
}
