package com.dataporter.migration.domain.error;

import com.dataporter.shared.error.DataPorterException;
import com.dataporter.shared.domain.FailureKind;
public final class MetadataMigrationException extends DataPorterException {
    public MetadataMigrationException(String message, Throwable cause) { super(message, cause); }
    @Override public FailureKind failureKind() { return FailureKind.METADATA; }
}
