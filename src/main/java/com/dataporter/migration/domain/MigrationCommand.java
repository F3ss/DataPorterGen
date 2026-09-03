package com.dataporter.migration.domain;

import com.dataporter.shared.domain.Endpoint;

public record MigrationCommand(Endpoint source, Endpoint target, MigrationOptions options) {}
