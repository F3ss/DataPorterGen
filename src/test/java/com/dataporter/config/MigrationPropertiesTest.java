package com.dataporter.config;

import com.dataporter.shared.domain.OperationMode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationPropertiesTest {
    @Test
    void emptyListsSelectAllCollections() {
        var properties = configuredProperties();

        assertThat(properties.toCommand().options().collectionSelection().selectsAll()).isTrue();
    }

    @Test
    void propertyListsAreNormalizedAtDomainBoundary() {
        var properties = configuredProperties();
        properties.setIncludeCollections(List.of(" customers ", "orders", "customers", " "));

        assertThat(properties.toCommand().options().collectionSelection().includeCollections())
                .containsExactly("customers", "orders");
    }

    @Test
    void springBinderAcceptsCommaSeparatedEnvironmentStyleValue() {
        var source = new MapConfigurationPropertySource(Map.of(
                "migration.source.uri", "mongodb://source:27017",
                "migration.source.database", "source",
                "migration.target.uri", "mongodb://target:27017",
                "migration.target.database", "target",
                "migration.include-collections", "customers, orders,customers",
                "migration.exclude-collections", ""));

        MigrationProperties properties = new Binder(source)
                .bind("migration", Bindable.of(MigrationProperties.class))
                .orElseThrow(() -> new AssertionError("migration properties were not bound"));

        assertThat(properties.toCommand().options().collectionSelection().includeCollections())
                .containsExactly("customers", "orders");
        assertThat(properties.toCommand().options().collectionSelection().excludeCollections()).isEmpty();
    }

    @Test
    void bindsGenerationModeAndOptionsWithoutChangingMigrationDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "migration.mode", "GENERATE",
                "migration.source.uri", "mongodb://same:27017",
                "migration.source.database", "catalog",
                "migration.target.uri", "mongodb://same:27017",
                "migration.target.database", "catalog",
                "migration.generation.config-path", "custom.yml",
                "migration.generation.validate-only", "true",
                "migration.generation.allow-unproven-ids", "true"));
        MigrationProperties properties = new Binder(source).bind("migration", Bindable.of(MigrationProperties.class))
                .orElseThrow(() -> new AssertionError("generation properties were not bound"));

        assertThat(properties.getMode()).isEqualTo(OperationMode.GENERATE);
        assertThat(properties.generationConfigPath()).isEqualTo(Path.of("custom.yml"));
        assertThat(properties.toGenerationCommand().options().validateOnly()).isTrue();
        assertThat(properties.toGenerationCommand().options().allowUnprovenIds()).isTrue();
        assertThat(properties.generationReportPath()).isEqualTo(Path.of("reports/generation-report.json"));
    }

    @Test void generationDefaultsToProvenIdsOnly() {
        assertThat(configuredProperties().toGenerationCommand().options().allowUnprovenIds()).isFalse();
    }

    private MigrationProperties configuredProperties() {
        var properties = new MigrationProperties();
        properties.getSource().setUri("mongodb://source:27017");
        properties.getSource().setDatabase("source");
        properties.getTarget().setUri("mongodb://target:27017");
        properties.getTarget().setDatabase("target");
        return properties;
    }
}
