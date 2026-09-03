package com.dataporter.migration.domain;

import com.dataporter.shared.bson.BsonPayload;

public record ViewDefinition(String name, String viewOn, BsonPayload options) {}
