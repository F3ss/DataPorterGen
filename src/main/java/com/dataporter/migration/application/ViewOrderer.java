package com.dataporter.migration.application;

import com.dataporter.migration.domain.ViewDefinition;
import com.dataporter.shared.error.SourceInspectionException;

import java.util.*;

public final class ViewOrderer {
    public List<ViewDefinition> order(List<ViewDefinition> views) {
        Map<String, ViewDefinition> byName = new LinkedHashMap<>();
        views.forEach(view -> byName.put(view.name(), view));
        List<ViewDefinition> ordered = new ArrayList<>();
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (ViewDefinition view : views) visit(view, byName, visiting, visited, ordered);
        return List.copyOf(ordered);
    }

    private void visit(ViewDefinition view, Map<String, ViewDefinition> views, Set<String> visiting,
                       Set<String> visited, List<ViewDefinition> ordered) {
        if (visited.contains(view.name())) return;
        if (!visiting.add(view.name())) throw new SourceInspectionException("Cyclic view dependency involving " + view.name());
        ViewDefinition dependency = views.get(view.viewOn());
        if (dependency != null) visit(dependency, views, visiting, visited, ordered);
        visiting.remove(view.name());
        visited.add(view.name());
        ordered.add(view);
    }
}
