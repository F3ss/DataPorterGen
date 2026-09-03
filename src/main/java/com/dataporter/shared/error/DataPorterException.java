package com.dataporter.shared.error;

import com.dataporter.shared.domain.FailureKind;
import com.dataporter.shared.security.SecretMasker;

public class DataPorterException extends RuntimeException {
    public DataPorterException(String message) { super(SecretMasker.redact(message)); }
    public DataPorterException(String message, Throwable cause) { super(SecretMasker.redact(message), cause); }
    public FailureKind failureKind() { return FailureKind.UNKNOWN; }
}
