package com.dataporter.config;

import com.dataporter.generation.domain.GenerationCommand;
import com.dataporter.generation.domain.GenerationOptions;
import com.dataporter.migration.domain.CollectionSelection;
import com.dataporter.migration.domain.ConsistencyMode;
import com.dataporter.migration.domain.ExistingTargetStrategy;
import com.dataporter.migration.domain.MigrationCommand;
import com.dataporter.migration.domain.MigrationOptions;
import com.dataporter.migration.domain.RetrySettings;
import com.dataporter.migration.domain.VerificationLevel;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.domain.OperationMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties("migration")
public class MigrationProperties {
    @NotNull private OperationMode mode = OperationMode.MIGRATE;
    @Valid @NotNull private Connection source = new Connection();
    @Valid @NotNull private Connection target = new Connection();
    @NotNull private ExistingTargetStrategy existingTargetStrategy = ExistingTargetStrategy.FAIL_IF_EXISTS;
    @NotNull private ConsistencyMode consistencyStrategy = ConsistencyMode.BASIC;
    @Min(1) @Max(100000) private int batchSize = 1000;
    @Min(1) @Max(64) private int parallelism = 2;
    private boolean verificationEnabled = true;
    @NotNull private VerificationLevel verificationLevel = VerificationLevel.METADATA_AND_COUNTS;
    private List<String> includeCollections = new ArrayList<>();
    private List<String> excludeCollections = new ArrayList<>();
    private boolean continueOnCollectionError;
    @Valid @NotNull private Retry retry = new Retry();
    @NotBlank private String reportPath = "reports/migration-report.json";
    @Valid @NotNull private Generation generation = new Generation();

    public MigrationCommand toCommand() {
        return new MigrationCommand(new Endpoint(source.uri, source.database), new Endpoint(target.uri, target.database),
                new MigrationOptions(existingTargetStrategy, consistencyStrategy, batchSize, parallelism,
                        verificationEnabled, verificationLevel,
                        CollectionSelection.from(includeCollections, excludeCollections), continueOnCollectionError,
                        new RetrySettings(retry.maxAttempts, retry.initialDelay.toMillis(), retry.maxDelay.toMillis())));
    }

    public GenerationCommand toGenerationCommand() {
        return new GenerationCommand(new Endpoint(source.uri, source.database), new Endpoint(target.uri, target.database),
                new GenerationOptions(generation.validateOnly, generation.allowUnprovenIds, generation.onlyConfiguredFields));
    }

    public Path migrationReportPath() { return Path.of(reportPath); }

    public Path generationConfigPath() { return Path.of(generation.configPath); }

    public Path generationReportPath() {
        return Path.of("reports/migration-report.json".equals(reportPath)
                ? "reports/generation-report.json" : reportPath);
    }

    public OperationMode getMode() { return mode; }
    public void setMode(OperationMode mode) { this.mode = mode; }

    public Connection getSource() { return source; }
    public void setSource(Connection source) { this.source = source; }
    public Connection getTarget() { return target; }
    public void setTarget(Connection target) { this.target = target; }
    public ExistingTargetStrategy getExistingTargetStrategy() { return existingTargetStrategy; }
    public void setExistingTargetStrategy(ExistingTargetStrategy v) { existingTargetStrategy = v; }
    public ConsistencyMode getConsistencyStrategy() { return consistencyStrategy; }
    public void setConsistencyStrategy(ConsistencyMode v) { consistencyStrategy = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { batchSize = v; }
    public int getParallelism() { return parallelism; }
    public void setParallelism(int v) { parallelism = v; }
    public boolean isVerificationEnabled() { return verificationEnabled; }
    public void setVerificationEnabled(boolean v) { verificationEnabled = v; }
    public VerificationLevel getVerificationLevel() { return verificationLevel; }
    public void setVerificationLevel(VerificationLevel v) { verificationLevel = v; }
    public List<String> getIncludeCollections() { return includeCollections; }
    public void setIncludeCollections(List<String> v) { includeCollections = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public List<String> getExcludeCollections() { return excludeCollections; }
    public void setExcludeCollections(List<String> v) { excludeCollections = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public boolean isContinueOnCollectionError() { return continueOnCollectionError; }
    public void setContinueOnCollectionError(boolean v) { continueOnCollectionError = v; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }
    public String getReportPath() { return reportPath; }
    public void setReportPath(String reportPath) { this.reportPath = reportPath; }
    public Generation getGeneration() { return generation; }
    public void setGeneration(Generation generation) { this.generation = generation; }

    public static class Connection {
        @NotBlank private String uri;
        @NotBlank private String database;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
    }

    public static class Retry {
        @Min(1) @Max(10) private int maxAttempts = 3;
        @NotNull private Duration initialDelay = Duration.ofMillis(500);
        @NotNull private Duration maxDelay = Duration.ofSeconds(5);
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getInitialDelay() { return initialDelay; }
        public void setInitialDelay(Duration initialDelay) { this.initialDelay = initialDelay; }
        public Duration getMaxDelay() { return maxDelay; }
        public void setMaxDelay(Duration maxDelay) { this.maxDelay = maxDelay; }
    }

    public static class Generation {
        @NotBlank private String configPath = "./generation.yml";
        private boolean validateOnly;
        private boolean allowUnprovenIds;
        private boolean onlyConfiguredFields;
        public String getConfigPath() { return configPath; }
        public void setConfigPath(String configPath) { this.configPath = configPath; }
        public boolean isValidateOnly() { return validateOnly; }
        public void setValidateOnly(boolean validateOnly) { this.validateOnly = validateOnly; }
        public boolean isAllowUnprovenIds() { return allowUnprovenIds; }
        public void setAllowUnprovenIds(boolean allowUnprovenIds) { this.allowUnprovenIds = allowUnprovenIds; }
        public boolean isOnlyConfiguredFields() { return onlyConfiguredFields; }
        public void setOnlyConfiguredFields(boolean onlyConfiguredFields) { this.onlyConfiguredFields = onlyConfiguredFields; }
    }

}
