package com.dataporter.shared.error;
import com.dataporter.shared.domain.FailureKind;
public final class SourceInspectionException extends DataPorterException {
    public SourceInspectionException(String message) { super(message); }
    public SourceInspectionException(String message, Throwable cause) { super(message, cause); }
    @Override public FailureKind failureKind() { return FailureKind.SOURCE_INSPECTION; }
}
