package com.dataporter.migration.domain;

public record RetrySettings(int maxAttempts, long initialDelayMillis, long maxDelayMillis) {}
