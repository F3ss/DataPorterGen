package com.dataporter.adapters.mongo;

import com.dataporter.generation.domain.GenerationRule;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.SharedDateDefinition;
import com.dataporter.generation.domain.TemplateFacts;
import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.generation.domain.UnconfiguredFields;
import com.dataporter.generation.domain.error.GenerationException;
import com.dataporter.generation.ports.out.GenerationBsonEngine;
import com.dataporter.shared.bson.BsonPayload;

import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.BsonValue;

import java.util.Map;
import java.util.Set;

public final class MongoGenerationBsonEngine implements GenerationBsonEngine {
    private static final int MAX_BSON_BYTES = 16 * 1024 * 1024;
    private final BsonPointerOperations paths = new BsonPointerOperations();
    private final BsonRuleEvaluator evaluator = new BsonRuleEvaluator(paths);
    private final BsonTemplateInspector inspector = new BsonTemplateInspector();

    @Override public TemplateFacts inspect(BsonPayload template) {
        return inspector.inspect(template);
    }

    @Override public BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                                         Map<String, GenerationRule> fields, ResolvedIdStrategy idStrategy,
                                         Map<String, BsonPayload> sameIterationDocuments,
                                         Map<String, Long> sequenceStarts) {
        return generate(collection, iteration, seed, template, fields, idStrategy, sameIterationDocuments,
                sequenceStarts, Map.of(), null, 1, null, UnconfiguredFields.SNAPSHOT);
    }

    @Override public BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                                         Map<String, GenerationRule> fields, ResolvedIdStrategy idStrategy,
                                         Map<String, BsonPayload> sameIterationDocuments,
                                         Map<String, Long> sequenceStarts,
                                         String batchUniqueRandomStringPath, int batchSize) {
        return generate(collection, iteration, seed, template, fields, idStrategy, sameIterationDocuments,
                sequenceStarts, Map.of(), batchUniqueRandomStringPath, batchSize, null, UnconfiguredFields.SNAPSHOT);
    }

    @Override public BsonPayload generate(String collection, long iteration, long seed, BsonPayload template,
                                         Map<String, GenerationRule> fields, ResolvedIdStrategy idStrategy,
                                         Map<String, BsonPayload> sameIterationDocuments,
                                         Map<String, Long> sequenceStarts,
                                         Map<String, SharedDateDefinition> sharedDates,
                                         String batchUniqueRandomStringPath, int batchSize,
                                         Set<String> keepPaths, UnconfiguredFields unconfiguredFields) {
        try {
            BsonDocument document = MongoBson.decodeMutable(template);
            if (keepPaths != null) document = switch (unconfiguredFields) {
                case OMIT -> paths.retain(document, keepPaths);
                case DEFAULTS -> paths.blank(document, keepPaths);
                case RANDOM -> evaluator.randomize(document, keepPaths, collection, iteration, seed);
                // SNAPSHOT collections get a null keep-set from the orchestrator and never reach here.
                case SNAPSHOT -> document;
            };
            for (String path : evaluator.cachedEvaluationOrder(fields)) {
                Object value = evaluator.evaluate(fields.get(path), collection, iteration, seed, path, document,
                        sameIterationDocuments, sequenceStarts, sharedDates,
                        batchUniqueRandomStringPath, batchSize);
                if (value == BsonRuleEvaluator.OMIT) paths.remove(document, path);
                else paths.set(document, path, (BsonValue) value);
            }
            evaluator.applyResolvedId(document, collection, iteration, seed, idStrategy, sequenceStarts);
            BsonValue id = document.get("_id");
            if (id == null || id.isNull() || id.isArray() || id.isDocument())
                throw new GenerationException("Generated _id must be a required scalar BSON value in " + collection);
            BsonPayload result = MongoBson.encode(document);
            if (result.size() > MAX_BSON_BYTES) throw new GenerationException("Generated document exceeds MongoDB 16 MiB limit in " + collection);
            return result;
        } catch (GenerationException e) { throw e; }
        catch (RuntimeException e) { throw new GenerationException("Cannot generate document for " + collection + " at iteration " + iteration, e); }
    }

    @Override public BsonPayload constraintKey(BsonPayload payload, UniqueConstraint constraint) {
        BsonDocument document = MongoBson.decode(payload);
        BsonDocument key = new BsonDocument();
        for (String path : constraint.paths()) {
            BsonValue value = paths.get(document, path);
            key.put(path, value == null ? BsonNull.VALUE : value);
        }
        return MongoBson.encode(key);
    }

    @Override public void validateScalarId(BsonPayload payload, String collection) {
        BsonValue id = MongoBson.decode(payload).get("_id");
        if (id == null || id.isNull() || id.isArray() || id.isDocument())
            throw new GenerationException("Generated _id must be a required scalar BSON value in " + collection);
    }
}
