package com.dataporter.migration.application;

import com.dataporter.migration.domain.CollectionDefinition;
import com.dataporter.migration.domain.CollectionSelection;
import com.dataporter.migration.domain.IndexDefinition;
import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.domain.ObjectResult;
import com.dataporter.migration.domain.PlanSelectionResult;
import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.shared.domain.DatabaseObjectType;
import com.dataporter.shared.domain.ObjectStatus;
import com.dataporter.shared.error.ConfigurationException;

import java.util.*;

public final class MigrationPlanSelector {
    public PlanSelectionResult select(MigrationPlan discovered, CollectionSelection selection) {
        Objects.requireNonNull(discovered, "discovered plan");
        Objects.requireNonNull(selection, "collection selection");
        if (selection.isIncludeMode() && selection.isExcludeMode()) {
            throw new ConfigurationException("include-collections and exclude-collections cannot be used together; clear one of them");
        }

        Set<String> available = new TreeSet<>();
        discovered.collections().forEach(collection -> available.add(collection.name()));
        Set<String> configured = selection.isIncludeMode()
                ? selection.includeCollections() : selection.excludeCollections();
        Set<String> missing = new TreeSet<>(configured);
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            String property = selection.isIncludeMode() ? "include-collections" : "exclude-collections";
            throw new ConfigurationException("Unknown source collections in " + property + ": " + missing);
        }

        if (selection.selectsAll()) return new PlanSelectionResult(discovered, List.of());

        Set<String> selected = new HashSet<>();
        if (selection.isIncludeMode()) selected.addAll(selection.includeCollections());
        else {
            selected.addAll(available);
            selected.removeAll(selection.excludeCollections());
        }

        List<CollectionDefinition> collections = discovered.collections().stream()
                .filter(collection -> selected.contains(collection.name())).toList();
        List<IndexDefinition> indexes = discovered.indexes().stream()
                .filter(index -> selected.contains(index.collection())).toList();
        Set<String> resolvable = new HashSet<>(selected);
        Set<String> selectedViews = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (ViewDefinition view : discovered.views()) {
                if (!selectedViews.contains(view.name()) && resolvable.contains(view.viewOn())) {
                    selectedViews.add(view.name());
                    resolvable.add(view.name());
                    changed = true;
                }
            }
        } while (changed);
        List<ViewDefinition> views = discovered.views().stream()
                .filter(view -> selectedViews.contains(view.name())).toList();

        String detail = selection.isIncludeMode()
                ? "not selected by include-collections" : "excluded by exclude-collections";
        List<ObjectResult> skipped = new ArrayList<>();
        discovered.collections().stream().filter(collection -> !selected.contains(collection.name()))
                .forEach(collection -> skipped.add(new ObjectResult(collection.name(), DatabaseObjectType.COLLECTION,
                        ObjectStatus.SKIPPED, 0, 0, detail)));
        discovered.indexes().stream().filter(index -> !selected.contains(index.collection()))
                .forEach(index -> skipped.add(new ObjectResult(index.name(), DatabaseObjectType.INDEX,
                        ObjectStatus.SKIPPED, 0, 0, detail + " on " + index.collection())));
        discovered.views().stream().filter(view -> !selectedViews.contains(view.name()))
                .forEach(view -> skipped.add(new ObjectResult(view.name(), DatabaseObjectType.VIEW,
                        ObjectStatus.SKIPPED, 0, 0, detail + "; viewOn=" + view.viewOn())));

        return new PlanSelectionResult(new MigrationPlan(collections, indexes, views), skipped);
    }
}
