package com.dataporter.adapters.cli;

import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.migration.ports.out.MigrationProgressReporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public final class LoggingProgressReporter implements MigrationProgressReporter {
    private static final Logger log = LoggerFactory.getLogger(LoggingProgressReporter.class);
    private final ConcurrentHashMap<String, Sample> samples = new ConcurrentHashMap<>();

    public void stageStarted(String migrationId, String stage) {
        log.info("migrationId={} stage={} started", migrationId, stage);
        if ("COPY_DOCUMENTS".equals(stage)) {
            log.info("migrationId={} secondaryIndexes=deferred detail=User indexes are created after all document copying completes; only automatic _id_ indexes are expected during this stage",
                    migrationId);
        }
    }

    public void collectionProgress(String migrationId, String collection, long documents, long bytes) {
        long now = System.nanoTime();
        Sample previous = samples.get(collection);
        if (previous == null || now - previous.timeNanos >= 5_000_000_000L) {
            double seconds = previous == null ? 0 : (now - previous.timeNanos) / 1_000_000_000.0;
            double rate = previous == null ? 0 : (documents - previous.documents) / seconds;
            samples.put(collection, new Sample(now, documents));
            log.info("migrationId={} collection={} documents={} bytes={} rateDocsPerSecond={}",
                    migrationId, collection, documents, bytes, Math.round(rate));
        }
    }

    public void indexStarted(String migrationId, String collection, String index, int ordinal, int total) {
        log.info("migrationId={} stage=CREATE_INDEXES collection={} index={} progress={}/{} started",
                migrationId, collection, index, ordinal, total);
    }

    public void completed(MigrationReport report) {
        log.info("migrationId={} status={} durationMs={} objects={} errors={} reportSuccessful={}",
                report.migrationId(), report.status(), report.durationMillis(), report.objects().size(),
                report.errors().size(), report.successful());
        report.errors().forEach(issue -> log.error("migrationId={} stage={} object={} error={}",
                report.migrationId(), issue.stage(), issue.object(), issue.message()));
    }

    private record Sample(long timeNanos, long documents) {}
}
