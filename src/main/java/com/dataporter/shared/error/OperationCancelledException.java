package com.dataporter.shared.error;
import com.dataporter.shared.domain.FailureKind;
public final class OperationCancelledException extends DataPorterException {
    public OperationCancelledException() { super("Migration was cancelled"); }
    @Override public FailureKind failureKind() { return FailureKind.CANCELLED; }
}
