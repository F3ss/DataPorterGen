package com.dataporter.shared.domain;

import com.dataporter.shared.error.DataPorterException;

public enum FailureKind {
    CONFIGURATION, SOURCE_CONNECTION, SOURCE_INSPECTION, TARGET_CONNECTION, TARGET_PREPARATION,
    DOCUMENT, METADATA, VERIFICATION, GENERATION, CANCELLED, UNKNOWN;

    public static FailureKind of(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof IllegalArgumentException) return CONFIGURATION;
            if (cause instanceof DataPorterException typed && typed.failureKind() != UNKNOWN) return typed.failureKind();
        }
        return UNKNOWN;
    }
}
