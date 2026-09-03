package com.dataporter.migration.domain;

import com.dataporter.shared.bson.BsonPayload;

public record IndexDefinition(String collection, String name, BsonPayload specification) {}
