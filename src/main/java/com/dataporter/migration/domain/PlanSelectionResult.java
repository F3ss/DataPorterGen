package com.dataporter.migration.domain;

import java.util.List;

public record PlanSelectionResult(MigrationPlan plan, List<ObjectResult> skipped) {
    public PlanSelectionResult { skipped = List.copyOf(skipped); }
}
