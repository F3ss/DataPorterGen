package com.dataporter.shared.error;
import com.dataporter.shared.domain.FailureKind;
public final class TargetPreparationException extends DataPorterException {
    public TargetPreparationException(String message) { super(message); }
    public TargetPreparationException(String message, Throwable cause) { super(message, cause); }
    @Override public FailureKind failureKind() { return FailureKind.TARGET_PREPARATION; }
}
