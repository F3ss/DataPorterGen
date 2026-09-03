package com.dataporter.migration.domain;

import com.dataporter.shared.bson.BsonPayload;

public record CollectionDefinition(String name, BsonPayload options) {}
