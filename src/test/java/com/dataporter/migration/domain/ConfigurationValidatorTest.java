package com.dataporter.migration.domain;

import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.ConfigurationException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ConfigurationValidatorTest {
    private final ConfigurationValidator validator = new ConfigurationValidator();

    @Test
    void acceptsSafeConfiguration() {
        assertThatCode(() -> validator.validate(command("mongodb://source:27017", "a",
                "mongodb://target:27017", "b", 500, 2))).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidPerformanceValues() {
        assertThatThrownBy(() -> validator.validate(command("mongodb://source:27017", "a",
                "mongodb://target:27017", "b", 0, 0)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("batchSize")
                .hasMessageContaining("parallelism");
    }

    @Test
    void rejectsSameClusterAndDatabaseEvenWhenCredentialsDiffer() {
        assertThatThrownBy(() -> validator.validate(command("mongodb://alice:secret@db:27017", "catalog",
                "mongodb://bob:other@db:27017", "catalog", 100, 1)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("source and target");
    }

    @Test
    void rejectsSameClusterThroughDefaultPortAndSeedOrderAliases() {
        assertThatThrownBy(() -> validator.validate(command("mongodb://db:27017", "catalog",
                "mongodb://db", "catalog", 100, 1)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("source and target");
        assertThatThrownBy(() -> validator.validate(command("mongodb://a:27017,b", "catalog",
                "mongodb://B:27017,A", "catalog", 100, 1)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("source and target");
    }

    @Test
    void allowsSameClusterNameWithDifferentHostSets() {
        assertThatCode(() -> validator.validate(command("mongodb://a:27017", "catalog",
                "mongodb://b:27017", "catalog", 100, 1))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTargetUriWithUnacknowledgedWriteConcern() {
        assertThatThrownBy(() -> validator.validate(command("mongodb://source:27017", "a",
                "mongodb://target:27017/b?replicaSet=rs0&w=0&journal=false", "b", 100, 1)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("acknowledged");
    }

    @Test
    void acceptsAcknowledgedTargetWriteConcern() {
        assertThatCode(() -> validator.validate(command("mongodb://source:27017", "a",
                "mongodb://target:27017/b?w=majority&wtimeoutMS=1000", "b", 100, 1))).doesNotThrowAnyException();
    }

    @Test
    void rejectsIncludeAndExcludeTogether() {
        var selection = CollectionSelection.from(List.of("customers"), List.of("events"));
        var options = new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC, 100, 1,
                true, VerificationLevel.METADATA_AND_COUNTS, selection, false,
                new RetrySettings(1, 0, 0));
        var command = new MigrationCommand(new Endpoint("mongodb://source:27017", "a"),
                new Endpoint("mongodb://target:27017", "b"), options);

        assertThatThrownBy(() -> validator.validate(command)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cannot be used together")
                .hasMessageContaining("clear one of them");
    }

    private MigrationCommand command(String sourceUri, String sourceDb, String targetUri, String targetDb,
                                     int batch, int parallelism) {
        return new MigrationCommand(new Endpoint(sourceUri, sourceDb), new Endpoint(targetUri, targetDb),
                new MigrationOptions(ExistingTargetStrategy.FAIL_IF_EXISTS, ConsistencyMode.BASIC,
                        batch, parallelism, true, VerificationLevel.METADATA_AND_COUNTS,
                        false, new RetrySettings(3, 10, 100)));
    }
}
