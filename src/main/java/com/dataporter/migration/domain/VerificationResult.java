package com.dataporter.migration.domain;

import java.util.List;

public record VerificationResult(boolean successful, List<String> differences) {
    public VerificationResult { differences = List.copyOf(differences); }
    public static VerificationResult skipped() { return new VerificationResult(true, List.of()); }
}
