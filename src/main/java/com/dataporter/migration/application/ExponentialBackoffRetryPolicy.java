package com.dataporter.migration.application;

import com.dataporter.migration.domain.RetrySettings;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

public final class ExponentialBackoffRetryPolicy {
    private final RetrySettings settings;
    private final Predicate<RuntimeException> transientFailure;

    public ExponentialBackoffRetryPolicy(RetrySettings settings, Predicate<RuntimeException> transientFailure) {
        this.settings = settings;
        this.transientFailure = transientFailure;
    }

    public <T> T execute(String operation, Callable<T> action) {
        long delay = settings.initialDelayMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                if (attempt >= settings.maxAttempts() || !transientFailure.test(e)) throw e;
                sleep(delay);
                delay = Math.min(settings.maxDelayMillis(), Math.max(delay + 1, delay * 2));
            } catch (Exception e) {
                throw new IllegalStateException(operation + " failed", e);
            }
        }
    }

    private void sleep(long millis) {
        if (millis == 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Retry interrupted", e); }
    }
}
