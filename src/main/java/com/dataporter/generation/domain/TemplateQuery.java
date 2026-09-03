package com.dataporter.generation.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable YAML/JSON value tree for a source template filter. */
public record TemplateQuery(Map<String, Object> document) {
    private static final Set<String> SERVER_SIDE_JAVASCRIPT = Set.of("$where", "$function", "$accumulator");
    private static final TemplateQuery MATCH_ALL = new TemplateQuery(Map.of());

    public TemplateQuery {
        if (document == null) throw new IllegalArgumentException("generation query must be an object");
        document = immutableDocument(document);
    }

    public static TemplateQuery matchAll() { return MATCH_ALL; }

    public boolean isMatchAll() { return document.isEmpty(); }

    private static Map<String, Object> immutableDocument(Map<?, ?> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name))
                throw new IllegalArgumentException("generation query object keys must be strings");
            if (SERVER_SIDE_JAVASCRIPT.contains(name))
                throw new IllegalArgumentException("generation query forbids server-side JavaScript operator " + name);
            copy.put(name, immutableValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) return value;
        if (value instanceof Map<?, ?> map) return immutableDocument(map);
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("unsupported generation query value type " + value.getClass().getSimpleName());
    }
}
