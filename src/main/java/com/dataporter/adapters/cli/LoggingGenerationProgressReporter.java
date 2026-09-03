package com.dataporter.adapters.cli;

import com.dataporter.generation.ports.out.GenerationProgressReporter;
import com.dataporter.shared.security.SecretMasker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class LoggingGenerationProgressReporter implements GenerationProgressReporter {
    private static final Logger log = LoggerFactory.getLogger(LoggingGenerationProgressReporter.class);
    private static final long PROGRESS_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private long lastProgressLogNanos;

    @Override public void warning(String generationId, String warning) {
        log.warn("generationId={} {}", generationId, SecretMasker.redact(warning));
    }

    @Override public synchronized void progress(String generationId, String stage, long completed, long total) {
        long now = System.nanoTime();
        if (completed != total && now - lastProgressLogNanos < PROGRESS_LOG_INTERVAL_NANOS) return;
        lastProgressLogNanos = now;
        log.info("generationId={} stage={} completed={} total={}", generationId, stage, completed, total);
    }
}
