package com.dataporter.adapters.mongo;

import com.dataporter.generation.ports.out.GenerationSchemaSource.FieldSchema;
import com.dataporter.generation.ports.out.GenerationSchemaSource.ScanCoverage;
import com.dataporter.shared.error.SourceInspectionException;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.BsonBinarySubType;

import java.util.*;

final class MongoFieldSchema {
    private final Map<String, Set<String>> fields = new TreeMap<>();
    private final Set<String> presentPaths = new HashSet<>();
    private final Set<String> configuredPaths;
    private final List<String> arrayIndexCandidates;
    private final BsonPointerOperations pointers = new BsonPointerOperations();
    private int pathCharacters;

    MongoFieldSchema(Set<String> configuredPaths) {
        this.configuredPaths = configuredPaths;
        arrayIndexCandidates = configuredPaths.stream().filter(path -> path.matches(".*/[+-]?\\d+(?:/.*)?")).toList();
    }

    void readValidator(BsonDocument validator) {
        BsonValue schema = validator.get("$jsonSchema");
        if (schema != null && schema.isDocument()) readSchema("", schema.asDocument(), false);
        for (String operator : List.of("$and", "$or")) {
            BsonValue branches = validator.get(operator);
            if (branches != null && branches.isArray())
                for (BsonValue branch : branches.asArray())
                    if (branch.isDocument()) readValidator(branch.asDocument());
        }
    }

    private void readSchema(String path, BsonDocument schema, boolean arrayItem) {
        if (!path.isEmpty() && !arrayItem) {
            add(path, schemaTypes(schema.get("bsonType", schema.get("type"))));
            if (configuredPaths.contains(path)) presentPaths.add(path);
        }
        BsonValue properties = schema.get("properties");
        if (properties != null && properties.isDocument())
            properties.asDocument().forEach((name, property) -> {
                if (property.isDocument()) readSchema(child(path, name), property.asDocument(), arrayItem);
            });
        BsonValue required = schema.get("required");
        if (required != null && required.isArray())
            for (BsonValue name : required.asArray())
                if (name.isString()) readSchema(child(path, name.asString().getValue()), new BsonDocument(), arrayItem);
        BsonValue items = schema.get("items");
        if (items != null && items.isDocument())
            readSchema(path + "/~2", items.asDocument(), true);
        else if (items != null && items.isArray())
            for (int index = 0; index < items.asArray().size(); index++) {
                BsonValue item = items.asArray().get(index);
                if (item.isDocument()) readSchema(path + "/" + index, item.asDocument(), true);
            }
        if (arrayItem) {
            for (String configured : arrayIndexCandidates)
                if (matchesArrayPath(path, configured)) presentPaths.add(configured);
        }
        for (String operator : List.of("allOf", "anyOf", "oneOf")) {
            BsonValue branches = schema.get(operator);
            if (branches != null && branches.isArray())
                for (BsonValue branch : branches.asArray())
                    if (branch.isDocument()) readSchema(path, branch.asDocument(), arrayItem);
        }
    }

    private static boolean matchesArrayPath(String schemaPath, String configuredPath) {
        if (schemaPath.equals(configuredPath)) return true;
        String[] declared = schemaPath.split("/", -1);
        String[] configured = configuredPath.split("/", -1);
        if (declared.length != configured.length) return false;
        for (int i = 1; i < declared.length; i++)
            if (!declared[i].equals(configured[i])
                    && !(declared[i].equals("~2") && validArrayIndex(configured[i]))) return false;
        return true;
    }

    private static boolean validArrayIndex(String token) {
        try { return Integer.parseInt(token) >= 0; }
        catch (NumberFormatException e) { return false; }
    }

    void observe(BsonDocument document) {
        observeDocument("", document);
        for (String path : arrayIndexCandidates)
            if (!presentPaths.contains(path) && pointers.get(document, path) != null) presentPaths.add(path);
    }

    private void observeDocument(String parent, BsonDocument document) {
        document.forEach((name, value) -> {
            String path = child(parent, name);
            String type = value.isBinary() && value.asBinary().getType() == BsonBinarySubType.UUID_STANDARD.getValue()
                    ? "UUID" : value.getBsonType().name();
            add(path, Set.of(type));
            if (configuredPaths.contains(path)) presentPaths.add(path);
            if (value.isDocument()) observeDocument(path, value.asDocument());
        });
    }

    private void add(String path, Set<String> types) {
        Set<String> known = fields.get(path);
        if (known == null) {
            pathCharacters += path.length();
            if (fields.size() >= 20_000 || pathCharacters > 1_000_000)
                throw new SourceInspectionException("Source field schema exceeds the field path limit");
            known = new TreeSet<>();
            fields.put(path, known);
        }
        known.addAll(types);
    }

    FieldSchema result(ScanCoverage coverage) {
        Map<String, Set<String>> result = new TreeMap<>();
        fields.forEach((path, types) -> result.put(path, types.isEmpty() ? Set.of("UNKNOWN") : Set.copyOf(types)));
        return new FieldSchema(Collections.unmodifiableMap(result), Set.copyOf(presentPaths), coverage);
    }

    private static String child(String parent, String name) { return parent + "/" + BsonPointerOperations.escape(name); }

    private static Set<String> schemaTypes(BsonValue types) {
        if (types == null) return Set.of();
        Set<String> result = new HashSet<>();
        if (types.isArray()) types.asArray().forEach(type -> result.addAll(schemaTypes(type)));
        else if (types.isString()) {
            String alias = types.asString().getValue();
            switch (alias) {
                case "bool" -> result.add("BOOLEAN");
                case "date" -> result.add("DATE_TIME");
                case "decimal" -> result.add("DECIMAL128");
                case "int" -> result.add("INT32");
                case "long" -> result.add("INT64");
                case "object" -> result.add("DOCUMENT");
                case "objectId" -> result.add("OBJECT_ID");
                case "binData" -> result.add("BINARY");
                case "regex" -> result.add("REGULAR_EXPRESSION");
                case "javascript" -> result.add("JAVASCRIPT");
                case "javascriptWithScope" -> result.add("JAVASCRIPT_WITH_SCOPE");
                case "minKey" -> result.add("MIN_KEY");
                case "maxKey" -> result.add("MAX_KEY");
                case "dbPointer" -> result.add("DB_POINTER");
                case "number" -> result.addAll(Set.of("DOUBLE", "DECIMAL128", "INT32", "INT64"));
                case "array", "double", "null", "string", "symbol", "timestamp", "undefined" ->
                        result.add(alias.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }
}
