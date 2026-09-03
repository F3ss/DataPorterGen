package com.dataporter.generation.domain;

import java.util.List;

public record UniqueConstraint(String collection, String name, List<String> paths,
                               boolean partial, boolean sparse, boolean nonSimpleCollation) {
    public UniqueConstraint { paths = List.copyOf(paths); }
}
