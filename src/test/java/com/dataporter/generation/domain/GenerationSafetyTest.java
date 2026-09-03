package com.dataporter.generation.domain;

import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.ConfigurationException;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenerationSafetyTest {
    @Test void allowsSameEndpointButRejectsMigrationFiltersAndDestructiveStrategies() {
        Endpoint same = new Endpoint("mongodb://same:27017", "catalog");
        var command = new GenerationCommand(same, same, new GenerationOptions(false, false));
        assertThatCode(() -> new GenerationConfigurationValidator().validate(command)).doesNotThrowAnyException();
        assertThatCode(() -> new GenerationModeValidator().validate(GenerationTargetMode.APPEND_TO_EXISTING, false)).doesNotThrowAnyException();

        assertThatThrownBy(() -> new GenerationModeValidator().validate(GenerationTargetMode.APPEND_TO_EXISTING, true))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("must be empty");
        assertThatThrownBy(() -> new GenerationModeValidator().validate(GenerationTargetMode.RECREATE_TARGET, false))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("forbidden");
        assertThatThrownBy(() -> new GenerationModeValidator().validate(GenerationTargetMode.MERGE_TARGET, false))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("MERGE");
    }

    @Test void rejectsUnacknowledgedTargetWriteConcern() {
        Endpoint source = new Endpoint("mongodb://same:27017", "catalog");
        Endpoint target = new Endpoint("mongodb://target:27017/catalog?w=0", "catalog");
        var command = new GenerationCommand(source, target,
                new GenerationOptions(false, false));
        assertThatThrownBy(() -> new GenerationConfigurationValidator().validate(command))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("acknowledged");
    }
}
