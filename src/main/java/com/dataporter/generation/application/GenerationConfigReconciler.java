package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.ports.out.GenerationConfigEditor;
import com.dataporter.generation.ports.out.GenerationConfigEditor.CollectionChanges;
import com.dataporter.generation.ports.out.GenerationSchemaSource;
import com.dataporter.shared.error.OperationCancelledException;
import com.dataporter.shared.ports.out.CancellationToken;

import java.util.*;
import java.util.function.Supplier;

public final class GenerationConfigReconciler {
    private final Supplier<? extends GenerationSchemaSource> sourceFactory;
    private final GenerationConfigEditor config;
    private final CancellationToken cancellation;

    public GenerationConfigReconciler(Supplier<? extends GenerationSchemaSource> sourceFactory,
                                      GenerationConfigEditor config, CancellationToken cancellation) {
        this.sourceFactory = sourceFactory;
        this.config = config;
        this.cancellation = cancellation;
    }

    public Result reconcile() {
        GenerationSpecValidator validator = new GenerationSpecValidator();
        GenerationSpec spec = config.readYaml();
        validator.validate(spec);
        checkCancelled();
        Map<String, NavigableSet<String>> absent = new LinkedHashMap<>();
        List<Map<String, Set<String>>> sourceFields = new ArrayList<>();
        List<String> emptyCollections = new ArrayList<>();
        try (GenerationSchemaSource source = sourceFactory.get()) {
            source.checkConnection();
            for (CollectionGenerationSpec collection : spec.collections()) {
                checkCancelled();
                Set<String> paths = collection.fields().keySet();
                var schema = source.inspectFields(collection.name(), paths, spec.batchSize(), cancellation);
                absent.put(collection.name(), obsoleteFields(paths, schema));
                sourceFields.add(schema.fields());
                if (!schema.absenceKnown()) emptyCollections.add(collection.name());
            }
        }

        List<CollectionChanges> changes = new ArrayList<>();
        Map<String, Set<String>> retained = preserveReferencedRules(spec, absent);
        int obsoleteCount = 0;
        int missingCount = 0;
        for (int i = 0; i < spec.collections().size(); i++) {
            CollectionGenerationSpec collection = spec.collections().get(i);
            Set<String> obsolete = absent.get(collection.name());
            Set<String> activePaths = new HashSet<>(collection.fields().keySet());
            activePaths.removeAll(obsolete);
            Map<String, Set<String>> missing = missingFields(activePaths, sourceFields.get(i));
            changes.add(new CollectionChanges(obsolete, missing));
            obsoleteCount += obsolete.size();
            missingCount += missing.size();
        }
        checkCancelled();
        boolean updated = config.reconcile(spec, changes, corrected -> {
            validator.validate(corrected);
            checkCancelled();
        });
        return new Result(updated, obsoleteCount, missingCount, retained, List.copyOf(emptyCollections));
    }

    private static NavigableSet<String> obsoleteFields(Set<String> paths, GenerationSchemaSource.FieldSchema schema) {
        NavigableSet<String> obsolete = new TreeSet<>();
        if (schema.absenceKnown()) {
            obsolete.addAll(paths);
            obsolete.removeAll(schema.presentPaths());
        }
        return obsolete;
    }

    private static Map<String, Set<String>> missingFields(Set<String> paths, Map<String, Set<String>> fields) {
        Set<String> covered = new HashSet<>(paths);
        covered.add("/_id");
        Set<String> containers = new HashSet<>();
        covered.forEach(path -> {
            for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1))
                containers.add(path.substring(0, slash));
        });
        Map<String, Set<String>> suggestions = new TreeMap<>();
        fields.forEach((path, types) -> {
            if (!containers.contains(path) && !hasProvider(path, covered)) suggestions.put(path, types);
        });
        return suggestions;
    }

    private static boolean hasProvider(String path, Set<String> fields) {
        if (fields.contains(path)) return true;
        for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1))
            if (fields.contains(path.substring(0, slash))) return true;
        return false;
    }

    private static Map<String, Set<String>> preserveReferencedRules(GenerationSpec spec,
                                                                    Map<String, NavigableSet<String>> absent) {
        Map<String, Set<String>> retained = new LinkedHashMap<>();
        Map<String, Map<String, GenerationRule>> rules = new HashMap<>();
        Deque<ActiveRule> pending = new ArrayDeque<>();
        for (CollectionGenerationSpec collection : spec.collections()) {
            String name = collection.name();
            rules.put(name, collection.fields());
            collection.fields().forEach((path, rule) -> {
                if (!absent.get(name).contains(path)) pending.addLast(new ActiveRule(name, rule));
            });
        }
        while (!pending.isEmpty()) {
            ActiveRule active = pending.removeFirst();
            GenerationPreflight.visitRefTargets(active.rule(), active.collection(), (collection, reference) -> {
                NavigableSet<String> obsolete = absent.get(collection);
                for (int end = reference.length(); end > 0; end = reference.lastIndexOf('/', end - 1)) {
                    String path = reference.substring(0, end);
                    if (obsolete.remove(path)) {
                        retained.computeIfAbsent(collection, ignored -> new LinkedHashSet<>()).add(path);
                        pending.addLast(new ActiveRule(collection, rules.get(collection).get(path)));
                    }
                }
                String prefix = reference + "/";
                var children = obsolete.tailSet(prefix, true).iterator();
                while (children.hasNext()) {
                    String path = children.next();
                    if (!path.startsWith(prefix)) break;
                    children.remove();
                    retained.computeIfAbsent(collection, ignored -> new LinkedHashSet<>()).add(path);
                    pending.addLast(new ActiveRule(collection, rules.get(collection).get(path)));
                }
            });
        }
        return retained;
    }

    private record ActiveRule(String collection, GenerationRule rule) {}

    private void checkCancelled() {
        if (cancellation.isCancellationRequested()) throw new OperationCancelledException();
    }

    public record Result(boolean updated, int obsolete, int missing, Map<String, Set<String>> retained,
                         List<String> emptyCollections) {}
}
