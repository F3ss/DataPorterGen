package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationRule.*;
import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.shared.error.ConfigurationException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class GenerationSpecValidator {
    public void validate(GenerationSpec spec) {
        Map<String, CollectionGenerationSpec> previous = new LinkedHashMap<>();
        for (CollectionGenerationSpec collection : spec.collections()) {
            validatePointers(collection);
            validateRules(collection, previous, spec.sharedDates().keySet());
            previous.put(collection.name(), collection);
        }
    }

    private void validatePointers(CollectionGenerationSpec collection) {
        List<String> paths = new ArrayList<>(collection.fields().keySet());
        for (String path : paths) validatePointer(path, collection.name());
        for (int i = 0; i < paths.size(); i++) for (int j = i + 1; j < paths.size(); j++) {
            String left = paths.get(i), right = paths.get(j);
            if (isAncestor(left, right) || isAncestor(right, left))
                fail("Conflicting field rules in " + collection.name() + ": " + left + " and " + right);
        }
    }

    private void validateRules(CollectionGenerationSpec collection, Map<String, CollectionGenerationSpec> previous,
                               Set<String> sharedDates) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        collection.fields().forEach((path, rule) -> {
            Set<String> local = new LinkedHashSet<>();
            walk(rule, collection, previous, sharedDates, local);
            Set<String> providers = new LinkedHashSet<>();
            local.forEach(ref -> { String provider = provider(ref, collection.fields().keySet()); if (provider != null) providers.add(provider); });
            providers.remove(path);
            dependencies.put(path, providers);
        });
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String path : dependencies.keySet()) visit(path, dependencies, visiting, visited);
    }

    private void walk(GenerationRule rule, CollectionGenerationSpec current,
                      Map<String, CollectionGenerationSpec> previous, Set<String> sharedDates, Set<String> local) {
        if (rule instanceof Sequence sequence && sequence.start() == SequenceStart.EXPLICIT && current.count() > 0) {
            try { Math.addExact(sequence.explicitStart(), Math.multiplyExact(current.count() - 1, sequence.step())); }
            catch (ArithmeticException e) { fail("Sequence overflows BSON int64 in " + current.name()); }
        } else if (rule instanceof Ref ref) validateRef(ref.collection(), ref.path(), current, previous, local);
        else if (rule instanceof DateTime date) {
            try {
                ZoneId zone = ZoneId.of(date.zone());
                Locale locale = locale(date.locale());
                if (date.output() == DateOutput.STRING) DateTimeFormatter.ofPattern(date.pattern(), locale).withZone(zone);
            } catch (RuntimeException e) { fail("Invalid dateTime pattern, zone, or locale in " + current.name() + ": " + e.getMessage()); }
            if (date.source() instanceof DateRef ref) validateRef(ref.collection(), ref.path(), current, previous, local);
            else if (date.source() instanceof SharedDateRef ref && !sharedDates.contains(ref.name()))
                fail("Unknown shared date " + ref.name() + " in " + current.name());
        } else if (rule instanceof Concat concat) {
            if (concat.parts().stream().anyMatch(this::containsAutoSequence))
                fail("AUTO_AFTER_TARGET_MAX sequence must be assigned to a document field in " + current.name());
            concat.parts().forEach(part -> walk(part, current, previous, sharedDates, local));
        }
        else if (rule instanceof WeightedChoice choice) {
            if (choice.choices().stream().anyMatch(item -> containsAutoSequence(item.value())))
                fail("AUTO_AFTER_TARGET_MAX sequence is unsupported inside weightedChoice in " + current.name());
            choice.choices().forEach(item -> walk(item.value(), current, previous, sharedDates, local));
        }
        else if (rule instanceof Array array) {
            if (containsAutoSequence(array.items()))
                fail("AUTO_AFTER_TARGET_MAX sequence is unsupported inside arrays in " + current.name());
            walk(array.items(), current, previous, sharedDates, local);
        }
        else if (rule instanceof ObjectValue object)
            object.fields().values().forEach(value -> walk(value, current, previous, sharedDates, local));
    }
    private boolean containsAutoSequence(GenerationRule rule) {
        if (rule instanceof Sequence sequence) return sequence.start() == SequenceStart.AUTO_AFTER_TARGET_MAX;
        if (rule instanceof Concat concat) return concat.parts().stream().anyMatch(this::containsAutoSequence);
        if (rule instanceof WeightedChoice choice)
            return choice.choices().stream().anyMatch(item -> containsAutoSequence(item.value()));
        if (rule instanceof Array array) return containsAutoSequence(array.items());
        if (rule instanceof ObjectValue object) return object.fields().values().stream().anyMatch(this::containsAutoSequence);
        return false;
    }

    private void validateRef(String collection, String path, CollectionGenerationSpec current,
                             Map<String, CollectionGenerationSpec> previous, Set<String> local) {
        validatePointer(path, current.name());
        if (collection == null) { local.add(path); return; }
        CollectionGenerationSpec referenced = previous.get(collection);
        if (referenced == null) fail("Collection " + current.name() + " references " + collection + " which is not earlier in the ordered list");
        if (referenced.count() < current.count())
            fail("Collection " + collection + " count must be at least " + current.name() + " count");
    }

    private void visit(String path, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(path)) return;
        if (!visiting.add(path)) fail("Cyclic field dependency involving " + path);
        for (String dependency : graph.getOrDefault(path, Set.of())) visit(dependency, graph, visiting, visited);
        visiting.remove(path); visited.add(path);
    }

    private static boolean isAncestor(String parent, String child) {
        return !parent.equals(child) && child.startsWith(parent.endsWith("/") ? parent : parent + "/");
    }
    private static String provider(String ref, Set<String> fields) {
        return fields.stream().filter(path -> path.equals(ref) || isAncestor(path, ref) || isAncestor(ref, path))
                .max(Comparator.comparingInt(String::length)).orElse(null);
    }
    private static Locale locale(String tag) {
        if ("ROOT".equals(tag)) return Locale.ROOT;
        return new Locale.Builder().setLanguageTag(tag).build();
    }
    private static void validatePointer(String path, String collection) {
        if (path == null || !path.startsWith("/") || path.equals("/") || path.endsWith("/"))
            fail("Invalid JSON Pointer in " + collection + ": " + path);
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '~') {
            if (i + 1 >= path.length() || (path.charAt(i + 1) != '0' && path.charAt(i + 1) != '1'))
                fail("Invalid JSON Pointer escape in " + collection + ": " + path);
            i++;
        }
    }
    private static void fail(String message) { throw new ConfigurationException(message); }
}
