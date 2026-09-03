package com.dataporter.migration.application;

import com.dataporter.migration.domain.RetrySettings;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class RetryPolicyTest {
    @Test
    void retriesOnlyTransientFailuresWithinBound() {
        var attempts = new AtomicInteger();
        var policy = new ExponentialBackoffRetryPolicy(new RetrySettings(3, 0, 0), e -> e instanceof Temporary);

        String result = policy.execute("ping", () -> {
            if (attempts.incrementAndGet() < 3) throw new Temporary();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryPermanentFailure() {
        var attempts = new AtomicInteger();
        var policy = new ExponentialBackoffRetryPolicy(new RetrySettings(3, 0, 0), e -> false);
        assertThatThrownBy(() -> policy.execute("write", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("permanent");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
    }

    private static final class Temporary extends RuntimeException {}
}
