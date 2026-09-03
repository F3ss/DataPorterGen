package com.dataporter.generation.application;

final class GenerationCounters {
    final long requested;
    long generated, written, snapshotTemplates, snapshotBytes, generatedBytes;
    boolean snapshotTruncated;
    GenerationCounters(long requested) { this.requested = requested; }
}
