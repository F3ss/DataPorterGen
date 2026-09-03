package com.dataporter.generation.domain.error;

import com.dataporter.shared.error.DataPorterException;
import com.dataporter.shared.domain.FailureKind;

public final class GenerationException extends DataPorterException {
    public GenerationException(String message) { super(message); }
    public GenerationException(String message, Throwable cause) { super(message, cause); }
    @Override public FailureKind failureKind() { return FailureKind.GENERATION; }
}
