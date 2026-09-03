package com.dataporter.shared.error;
import com.dataporter.shared.domain.FailureKind;
public final class ConfigurationException extends DataPorterException {
    public ConfigurationException(String message) { super(message); }
    public ConfigurationException(String message, Throwable cause) { super(message, cause); }
    @Override public FailureKind failureKind() { return FailureKind.CONFIGURATION; }
}
