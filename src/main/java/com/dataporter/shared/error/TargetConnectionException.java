package com.dataporter.shared.error;
import com.dataporter.shared.domain.FailureKind;
public final class TargetConnectionException extends DataPorterException {
    public TargetConnectionException(String message) { super(message); }
    public TargetConnectionException(String message, Throwable cause) { super(message, cause); }
    @Override public FailureKind failureKind() { return FailureKind.TARGET_CONNECTION; }
}
