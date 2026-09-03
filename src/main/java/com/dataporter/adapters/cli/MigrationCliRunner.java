package com.dataporter.adapters.cli;

import com.dataporter.adapters.config.GenerationConfigReader;
import com.dataporter.adapters.mongo.MongoGenerationBsonEngine;
import com.dataporter.adapters.mongo.MongoGenerationSource;
import com.dataporter.adapters.mongo.MongoMigrationSource;
import com.dataporter.adapters.mongo.MongoGenerationTarget;
import com.dataporter.adapters.mongo.MongoMigrationTarget;
import com.dataporter.adapters.mongo.MongoMigrationVerifier;
import com.dataporter.adapters.mongo.MongoTransientFailureClassifier;
import com.dataporter.adapters.reporting.JsonGenerationReportWriter;
import com.dataporter.adapters.reporting.JsonReportWriter;
import com.dataporter.adapters.snapshot.FileTemplateCatalogFactory;
import com.dataporter.config.MigrationProperties;
import com.dataporter.generation.application.GenerationOrchestrator;
import com.dataporter.generation.domain.GenerationCommand;
import com.dataporter.generation.domain.GenerationConfigurationValidator;
import com.dataporter.generation.domain.GenerationModeValidator;
import com.dataporter.generation.domain.GenerationTargetMode;
import com.dataporter.migration.application.MigrationService;
import com.dataporter.migration.domain.CollectionSelection;
import com.dataporter.migration.domain.ExistingTargetStrategy;
import com.dataporter.migration.domain.ConfigurationValidator;
import com.dataporter.migration.domain.MigrationCommand;
import com.dataporter.migration.domain.MigrationReport;
import com.dataporter.shared.domain.OperationMode;
import com.dataporter.shared.security.SecretMasker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class MigrationCliRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MigrationCliRunner.class);
    private final MigrationProperties properties;
    private volatile int exitCode = ExitCodes.CONFIGURATION;

    public MigrationCliRunner(MigrationProperties properties) { this.properties = properties; }

    @Override public void run(ApplicationArguments args) {
        if (properties.getMode() == OperationMode.GENERATE) runGeneration();
        else runMigration();
    }

    private void runMigration() {
        MigrationCommand command = properties.toCommand();
        try { new ConfigurationValidator().validate(command); }
        catch (RuntimeException e) {
            exitCode = ExitCodes.from(e);
            log.error("Migration configuration rejected: {}", SecretMasker.redact(e.getMessage()));
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread shutdownSignal = new Thread(() -> cancelled.set(true), "migration-shutdown-signal");
        Runtime.getRuntime().addShutdownHook(shutdownSignal);
        MongoMigrationSource source = null;
        MongoMigrationTarget target = null;
        boolean serviceOwnsClients = false;
        try {
            log.info("Starting migration source={} database={} target={} database={} strategy={} includeCollections={} excludeCollections={}",
                    command.source().safeUri(), command.source().database(), command.target().safeUri(),
                    command.target().database(), command.options().existingTargetStrategy(),
                    command.options().collectionSelection().includeCollections(),
                    command.options().collectionSelection().excludeCollections());
            source = new MongoMigrationSource(command.source());
            target = new MongoMigrationTarget(command.target());
            var service = new MigrationService(source, target,
                    new MongoMigrationVerifier(source, target, command.options().batchSize()),
                    new JsonReportWriter(properties.migrationReportPath()), new LoggingProgressReporter(), cancelled::get,
                    new MongoTransientFailureClassifier());
            serviceOwnsClients = true;
            MigrationReport report = service.migrate(command);
            exitCode = ExitCodes.from(report);
        } catch (RuntimeException e) {
            exitCode = ExitCodes.from(e);
            log.error("Migration failed before a report could be completed: {}: {}", e.getClass().getSimpleName(),
                    SecretMasker.redact(e.getMessage()));
        } finally {
            if (!serviceOwnsClients) {
                if (source != null) source.close();
                if (target != null) target.close();
            }
            try { Runtime.getRuntime().removeShutdownHook(shutdownSignal); }
            catch (IllegalStateException ignored) { }
        }
    }

    private void runGeneration() {
        GenerationCommand command;
        try {
            new GenerationModeValidator().validate(generationTargetMode(properties.getExistingTargetStrategy()),
                    !CollectionSelection.from(properties.getIncludeCollections(), properties.getExcludeCollections()).selectsAll());
            command = properties.toGenerationCommand();
            new GenerationConfigurationValidator().validate(command);
        } catch (RuntimeException e) {
            exitCode = ExitCodes.from(e);
            log.error("Generation configuration rejected: {}", SecretMasker.redact(e.getMessage()));
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread shutdownSignal = new Thread(() -> cancelled.set(true), "generation-shutdown-signal");
        Runtime.getRuntime().addShutdownHook(shutdownSignal);
        MongoGenerationSource source = null; MongoGenerationTarget target = null; boolean serviceOwnsClients = false;
        try {
            log.info("Starting generation source={} database={} target={} database={} validateOnly={}",
                    command.source().safeUri(), command.source().database(), command.target().safeUri(),
                    command.target().database(), command.options().validateOnly());
            source = new MongoGenerationSource(command.source()); target = new MongoGenerationTarget(command.target());
            GenerationOrchestrator service = new GenerationOrchestrator(source, target,
                    new GenerationConfigReader(properties.generationConfigPath()),
                    new FileTemplateCatalogFactory(), new MongoGenerationBsonEngine(),
                    new JsonGenerationReportWriter(properties.generationReportPath()),
                    new LoggingGenerationProgressReporter(), cancelled::get);
            serviceOwnsClients = true;
            exitCode = ExitCodes.from(service.generate(command));
        } catch (RuntimeException e) {
            exitCode = ExitCodes.from(e);
            log.error("Generation failed before a report could be completed: {}: {}", e.getClass().getSimpleName(), SecretMasker.redact(e.getMessage()));
        } finally {
            if (!serviceOwnsClients) { if (source != null) source.close(); if (target != null) target.close(); }
            try { Runtime.getRuntime().removeShutdownHook(shutdownSignal); } catch (IllegalStateException ignored) { }
        }
    }

    public int exitCode() { return exitCode; }

    private static GenerationTargetMode generationTargetMode(ExistingTargetStrategy strategy) {
        return switch (strategy) {
            case FAIL_IF_EXISTS -> GenerationTargetMode.APPEND_TO_EXISTING;
            case DROP_AND_RECREATE -> GenerationTargetMode.RECREATE_TARGET;
            case MERGE -> GenerationTargetMode.MERGE_TARGET;
        };
    }
}
