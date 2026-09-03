package com.dataporter.migration.domain;

import com.dataporter.shared.domain.DatabaseObjectType;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.domain.OperationIssue;
import com.dataporter.shared.domain.OperationStatus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationReportTest {
    @Test
    void partialObjectFailureMakesRunFailed() {
        var report = new MigrationReport("id", OperationStatus.FAILED, "mongodb://source:27017", "mongodb://target:27017",
                "a", "b", ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC,
                VerificationLevel.METADATA_AND_COUNTS, Instant.EPOCH, Instant.EPOCH,
                Map.of(), List.of(new ObjectResult("customers", DatabaseObjectType.COLLECTION,
                        ObjectStatus.PARTIAL, 3, 100, "safe restart is not guaranteed")),
                List.of(), List.of(new OperationIssue("COPY_DOCUMENTS", "customers", "write failed")),
                new VerificationResult(false, List.of("count mismatch")), false);

        assertThat(report.successful()).isFalse();
        assertThat(report.safeToRetry()).isFalse();
        assertThat(report.toString()).doesNotContain("password");
    }
}
