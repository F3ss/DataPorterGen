package com.dataporter.migration.ports.out;

import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.migration.domain.VerificationResult;
import com.dataporter.migration.domain.merge.MergeVerificationContext;

@FunctionalInterface
public interface MigrationVerifier {
    VerificationResult verify(MigrationPlan plan, VerificationLevel level);

    default VerificationResult verifyMerge(MigrationPlan plan, VerificationLevel level,
                                           MergeVerificationContext context) {
        return verify(plan, level);
    }
}
