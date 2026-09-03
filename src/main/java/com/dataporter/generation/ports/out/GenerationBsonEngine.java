package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.TemplateFacts;
import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.shared.bson.BsonPayload;

import java.util.Map;

public interface GenerationBsonEngine {
    TemplateFacts inspect(BsonPayload template);
    BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                         Map<String, GenerationRule> fields, ResolvedIdStrategy idStrategy,
                         Map<String, BsonPayload> sameIterationDocuments,
                         Map<String, Long> sequenceStarts);
    BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                         Map<String, GenerationRule> fields, ResolvedIdStrategy idStrategy,
                         Map<String, BsonPayload> sameIterationDocuments,
                         Map<String, Long> sequenceStarts, String batchUniqueRandomStringPath,
                         int batchSize);
    BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                         Map<String, GenerationRule> fields, ResolvedIdStrategy idStrategy,
                         Map<String, BsonPayload> sameIterationDocuments,
                         Map<String, Long> sequenceStarts,
                         Map<String, SharedDateDefinition> sharedDates,
                         String batchUniqueRandomStringPath, int batchSize);
    BsonPayload constraintKey(BsonPayload document, UniqueConstraint constraint);
    void validateScalarId(BsonPayload document, String collection);
}
