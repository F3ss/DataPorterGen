package com.dataporter.generation.ports.out;

public interface GenerationProgressReporter {
    void warning(String generationId, String warning);

    /** Monotonic stage progress; implementations throttle freely and must stay secret-free. */
    default void progress(String generationId, String stage, long completed, long total) { }

    static GenerationProgressReporter noop() {
        return (generationId, warning) -> { };
    }
}
