package com.dataporter.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static source scan enforcing the slice boundaries from AGENTS.md: domain packages stay free of
 * infrastructure, the migration and generation slices do not import each other, and MongoDB driver
 * types stay inside adapters.mongo. Unit-test-only scan; no Spring, Docker, or MongoDB required.
 */
class ArchitectureBoundaryTest {
    private static final Pattern IMPORT = Pattern.compile("import\\s+(?:static\\s+)?([\\w.]+)");

    private record Source(Path file, String packageName, List<String> imports, boolean test) { }

    @Test void sourceScanFindsProductionAndTestSources() {
        assertThat(sources()).hasSizeGreaterThan(100);
    }

    @Test void domainPackagesStayIndependentOfInfrastructure() {
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            if (!source.packageName().endsWith(".domain")) continue;
            for (String imported : source.imports())
                if (forbiddenInDomain(imported)) violations.add(source.file() + " imports " + imported);
        }
        assertThat(violations)
                .as("domain must not depend on Spring, MongoDB driver, org.bson, Jackson, logging, or filesystem APIs")
                .isEmpty();
    }

    @Test void featureSlicesDoNotImportEachOther() {
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            if (source.packageName().startsWith("com.dataporter.generation"))
                source.imports().stream().filter(imported -> imported.startsWith("com.dataporter.migration"))
                        .forEach(imported -> violations.add(source.file() + " imports " + imported));
            if (source.packageName().startsWith("com.dataporter.migration"))
                source.imports().stream().filter(imported -> imported.startsWith("com.dataporter.generation"))
                        .forEach(imported -> violations.add(source.file() + " imports " + imported));
        }
        assertThat(violations).as("generation and migration slices must not import each other").isEmpty();
    }

    @Test void mongoDriverStaysInsideMongoAdapters() {
        List<String> violations = new ArrayList<>();
        for (Source source : sources()) {
            boolean mongoAdapter = source.packageName().startsWith("com.dataporter.adapters.mongo");
            boolean integrationTest = source.test() && source.packageName().startsWith("com.dataporter.integration");
            for (String imported : source.imports()) {
                if (imported.startsWith("com.mongodb.") && !mongoAdapter && !integrationTest)
                    violations.add(source.file() + " imports " + imported);
                if (imported.startsWith("org.bson.") && !source.test() && !mongoAdapter)
                    violations.add(source.file() + " imports " + imported);
            }
        }
        assertThat(violations)
                .as("MongoDB driver types must stay inside adapters.mongo; integration tests may use them directly")
                .isEmpty();
    }

    private static boolean forbiddenInDomain(String imported) {
        return imported.startsWith("org.springframework.") || imported.startsWith("com.mongodb.")
                || imported.startsWith("org.bson.") || imported.startsWith("com.fasterxml.jackson.")
                || imported.startsWith("org.slf4j.") || imported.startsWith("java.util.logging")
                || imported.startsWith("java.nio.file.");
    }

    private static List<Source> sources() {
        List<Source> result = new ArrayList<>();
        scan(Path.of("src", "main", "java"), false, result);
        scan(Path.of("src", "test", "java"), true, result);
        return result;
    }

    private static void scan(Path root, boolean test, List<Source> result) {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> result.add(read(file, test)));
        } catch (IOException e) { throw new IllegalStateException("Cannot scan " + root, e); }
    }

    private static Source read(Path file, boolean test) {
        try {
            String packageName = "";
            List<String> imports = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("package "))
                    packageName = line.substring("package ".length(), line.length() - 1).trim();
                Matcher matcher = IMPORT.matcher(line.trim());
                if (matcher.lookingAt()) imports.add(matcher.group(1));
            }
            return new Source(file, packageName, imports, test);
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + file, e); }
    }
}
