package com.dataporter.shared.error;
import com.dataporter.shared.domain.FailureKind;
public final class SourceConnectionException extends DataPorterException {
    public SourceConnectionException(String message, Throwable cause) { super(message, cause); }
    @Override public FailureKind failureKind() { return FailureKind.SOURCE_CONNECTION; }
}
