package com.dataporter.bootstrap;

import com.dataporter.adapters.cli.ExitCodes;
import com.dataporter.adapters.cli.MigrationCliRunner;
import com.dataporter.config.MigrationProperties;
import com.dataporter.shared.security.SecretMasker;

import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

@SpringBootApplication(scanBasePackages = "com.dataporter")
@EnableConfigurationProperties(MigrationProperties.class)
public class DataPorterGenApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.println(help());
            return;
        }
        int code = ExitCodes.CONFIGURATION;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DataPorterGenApplication.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run(args)) {
            code = context.getBean(MigrationCliRunner.class).exitCode();
        } catch (RuntimeException e) {
            System.err.println("Configuration/startup error: " + SecretMasker.redact(rootMessage(e)));
        }
        System.exit(code);
    }

    static String help() {
        return """
                DataPorterGen (Java 21)
                Currently supported database: MongoDB
                Source (all modes):
                  --migration.source.uri=mongodb://host:27017
                  --migration.source.database=source_db
                Target (MIGRATE and GENERATE):
                  --migration.target.uri=mongodb://host:27017
                  --migration.target.database=target_db
                Important options:
                  --migration.mode=MIGRATE|GENERATE|VALIDATE_CONFIG
                  --migration.existing-target-strategy=FAIL_IF_EXISTS|DROP_AND_RECREATE|MERGE
                  --migration.batch-size=1000 --migration.parallelism=2
                  --migration.verification-level=METADATA_AND_COUNTS|FULL
                  --migration.include-collections=customers,orders
                  --migration.exclude-collections=events,temp_data
                  --migration.report-path=reports/migration-report.json
                Generate mode:
                  --migration.mode=GENERATE
                  --migration.generation.config-path=./generation.yml
                  --migration.generation.validate-only=false
                  --migration.generation.allow-unproven-ids=false (set true to allow a constant _id)
                  --migration.generation.validate-field-formats=true
                  --migration.generation.update-field-formats=false
                Config validation mode:
                  --migration.mode=VALIDATE_CONFIG
                  --migration.generation.config-path=./generation.yml (updates YAML in place)
                  --migration.generation.validation-max-documents=10000 (per collection)
                Environment equivalents include SOURCE_MONGODB_URI, SOURCE_DATABASE,
                TARGET_MONGODB_URI, TARGET_DATABASE, TARGET_STRATEGY,
                VERIFICATION_LEVEL,
                INCLUDE_COLLECTIONS, EXCLUDE_COLLECTIONS, MIGRATION_MODE,
                GENERATION_CONFIG_PATH, GENERATION_VALIDATE_ONLY, GENERATION_ALLOW_UNPROVEN_IDS,
                GENERATION_VALIDATE_FIELD_FORMATS, GENERATION_UPDATE_FIELD_FORMATS,
                and GENERATION_VALIDATION_MAX_DOCUMENTS.
                """;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return String.valueOf(current.getMessage());
    }
}
