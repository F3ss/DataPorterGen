package com.dataporter.shared.domain;

import com.dataporter.shared.security.SecretMasker;

public record OperationIssue(String stage, String object, String message, FailureKind failureKind) {
    public OperationIssue {
        message = SecretMasker.redact(message);
    }

    public OperationIssue(String stage, String object, String message) {
        this(stage, object, message, FailureKind.UNKNOWN);
    }
}
