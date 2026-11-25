package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.GenerationSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public interface GenerationConfigEditor {
    GenerationSpec readYaml();

    boolean reconcile(GenerationSpec original, List<CollectionChanges> changes, Consumer<GenerationSpec> validate);

    record CollectionChanges(Set<String> obsolete, Map<String, Set<String>> missing) {}
}
