package com.dataporter.generation.application;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.domain.GenerationSpec;
import com.dataporter.generation.domain.ResolvedIdStrategy;
import com.dataporter.generation.domain.UniqueConstraint;
import com.dataporter.generation.domain.error.GenerationException;
import com.dataporter.generation.ports.out.GenerationBsonEngine;
import com.dataporter.generation.ports.out.GenerationProgressReporter;
import com.dataporter.generation.ports.out.GenerationTarget;
import com.dataporter.generation.ports.out.TemplateCatalog;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.error.OperationCancelledException;
import com.dataporter.shared.ports.out.CancellationToken;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class GenerationBatchExecutor {
    private final GenerationTarget target;
    private final GenerationBsonEngine bson;
    private final GenerationProgressReporter progress;
    private final CancellationToken cancellation;
    private final TemplateSelector templateSelector = new TemplateSelector();

    GenerationBatchExecutor(GenerationTarget target, GenerationBsonEngine bson,
                            GenerationProgressReporter progress, CancellationToken cancellation) {
        this.target = target; this.bson = bson; this.progress = progress; this.cancellation = cancellation;
    }

    void upsert(GenerationSpec spec, long seed, TemplateCatalog catalog,
                Map<String,ResolvedIdStrategy> ids, Map<String,GenerationPreflight.RandomStringId> randomStringIds,
                Map<String,Long> starts, Map<String,Set<String>> keepSets,
                Map<String,List<UniqueConstraint>> constraints,
                Map<String,GenerationCounters> counters, AtomicBoolean writeAttempted, String generationId) {
        long iterations = spec.collections().stream().mapToLong(CollectionGenerationSpec::count).max().orElse(0);
        long blocks = (iterations + spec.batchSize() - 1) / spec.batchSize();
        long plannedDocuments = spec.collections().stream().mapToLong(CollectionGenerationSpec::count).sum();
        Map<String,List<UniqueConstraint>> secondaryConstraints = new LinkedHashMap<>();
        constraints.forEach((name, list) -> secondaryConstraints.put(name, list.stream()
                .filter(constraint -> !constraint.name().equals("_id_")).toList()));
        int parallelism = spec.parallelism(), capacity = Math.max(1, parallelism * 2);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        CompletionService<Block> completed = new ExecutorCompletionService<>(executor);
        long submitted = 0, received = 0, documents = 0;
        try {
            while (submitted < Math.min(blocks, capacity))
                completed.submit(blockTask(spec, seed, catalog, ids, randomStringIds, starts, keepSets, secondaryConstraints,
                        submitted++ * spec.batchSize(), iterations, writeAttempted));
            while (received < blocks) {
                checkCancelled(); Block result;
                try { result = completed.take().get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OperationCancelledException(); }
                catch (ExecutionException e) { throw unwrap(e.getCause()); }
                for (BlockTotals total : result.totals()) {
                    GenerationCounters count = counters.get(total.collection());
                    count.generated += total.generated(); count.written += total.written();
                    count.generatedBytes += total.generatedBytes();
                    documents += total.generated();
                }
                received++;
                progress.progress(generationId, GenerationOrchestrator.STAGES.get(10), documents, plannedDocuments);
                if (submitted < blocks)
                    completed.submit(blockTask(spec, seed, catalog, ids, randomStringIds, starts, keepSets, secondaryConstraints,
                            submitted++ * spec.batchSize(), iterations, writeAttempted));
            }
        } finally {
            executor.shutdownNow();
            try { executor.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private Callable<Block> blockTask(GenerationSpec spec,long seed,TemplateCatalog catalog,
                                      Map<String,ResolvedIdStrategy> ids,Map<String,GenerationPreflight.RandomStringId> randomStringIds,
                                      Map<String,Long> starts,Map<String,Set<String>> keepSets,Map<String,List<UniqueConstraint>> secondaryConstraints,
                                      long blockStart,long iterations,AtomicBoolean writeAttempted) {
        return () -> {
            long blockEnd = Math.min(blockStart + spec.batchSize(), iterations);
            List<CollectionBlock> work = new ArrayList<>();
            for (CollectionGenerationSpec collection : spec.collections()) {
                long end = Math.min(collection.count(), blockEnd);
                if (blockStart >= end) continue;
                work.add(new CollectionBlock(collection, end, ids.get(collection.name()),
                        Optional.ofNullable(randomStringIds.get(collection.name())).map(GenerationPreflight.RandomStringId::path).orElse(null),
                        Math.toIntExact(Math.max(1, Math.min(collection.count(), spec.batchSize())))));
            }
            // Each worker owns an exclusive byte share of maxInFlightMegabytes, so materialization is bounded
            // before any waiting can occur; logical blocks are split into physical write batches by that share.
            long totalBudgetBytes = spec.maxInFlightMegabytes() * 1024L * 1024L;
            long shareBytes = Math.max(1, totalBudgetBytes / Math.max(1, spec.parallelism()));
            long physicalBatchBytes = Math.min(shareBytes, 32L * 1024 * 1024);
            Map<String,List<BsonPayload>> documents = new LinkedHashMap<>(); long size = 0;
            Map<String,BsonPayload> current = new LinkedHashMap<>();
            List<BlockTotals> totals = new ArrayList<>();
            for (long iteration = blockStart; iteration < blockEnd; iteration++) {
                checkCancelled();
                current.clear();
                for (CollectionBlock item : work) if (iteration < item.end()) {
                    CollectionGenerationSpec collection = item.spec();
                    BsonPayload payload = bson.generate(collection.name(), iteration, seed,
                            selectedTemplate(spec, seed, catalog, collection.name(), iteration), collection.fields(),
                            item.idStrategy(), current, starts, spec.sharedDates(), item.batchUniquePath(),
                            item.batchCapacity(), keepSets == null ? null : keepSets.get(collection.name()),
                            collection.unconfiguredFields());
                    if (payload.size() > totalBudgetBytes)
                        throw new GenerationException("Generated document for " + collection.name()
                                + " (" + payload.size() + " bytes) exceeds maxInFlightMegabytes="
                                + spec.maxInFlightMegabytes());
                    current.put(collection.name(), payload);
                    if (size > 0 && size + payload.size() > physicalBatchBytes) {
                        writeDocuments(documents, secondaryConstraints, writeAttempted, totals);
                        documents = new LinkedHashMap<>(); size = 0;
                    }
                    documents.computeIfAbsent(collection.name(), ignored -> new ArrayList<>()).add(payload);
                    size += payload.size();
                }
            }
            if (!documents.isEmpty()) writeDocuments(documents, secondaryConstraints, writeAttempted, totals);
            return new Block(totals);
        };
    }

    private void writeDocuments(Map<String,List<BsonPayload>> documents,
                                Map<String,List<UniqueConstraint>> secondaryConstraints,
                                AtomicBoolean writeAttempted, List<BlockTotals> totals) {
        for (Map.Entry<String,List<BsonPayload>> entry : documents.entrySet()) {
            String collection = entry.getKey(); List<BsonPayload> payloads = entry.getValue();
            long collectionBytes = 0;
            for (BsonPayload payload : payloads) collectionBytes += payload.size();
            List<UniqueConstraint> secondary = secondaryConstraints.getOrDefault(collection, List.of());
            if (!secondary.isEmpty()) {
                UniqueConstraint idConstraint = new UniqueConstraint(collection, "_id_", List.of("/_id"), false, false, false);
                List<BsonPayload> idKeys = new ArrayList<>(payloads.size());
                for (BsonPayload payload : payloads) idKeys.add(bson.constraintKey(payload, idConstraint));
                for (UniqueConstraint constraint : secondary) {
                    List<BsonPayload> keys = new ArrayList<>(payloads.size());
                    for (BsonPayload payload : payloads) keys.add(bson.constraintKey(payload, constraint));
                    if (target.constraintKeyConflicts(constraint, keys, idKeys))
                        throw new GenerationException("Generated key already exists in target for " + collection + "." + constraint.name());
                }
            }
            writeAttempted.set(true);
            target.upsert(new DataBatch(collection, List.copyOf(payloads), collectionBytes));
            totals.add(new BlockTotals(collection, payloads.size(), payloads.size(), collectionBytes));
        }
    }

    // Template selection must stay identical to GenerationPreflight.selectedTemplate: generated
    // values are coordinate-derived and the write phase must observe the same templates as coverage.
    private BsonPayload selectedTemplate(GenerationSpec spec, long seed, TemplateCatalog catalog,
                                         String collection, long iteration) {
        long count = catalog.count(collection);
        long ordinal = templateSelector.select(spec.templateSelection(), seed, collection, iteration, count);
        return catalog.get(collection, ordinal);
    }

    private void checkCancelled() { if (cancellation.isCancellationRequested()) throw new OperationCancelledException(); }
    private static RuntimeException unwrap(Throwable error) { return error instanceof RuntimeException runtime ? runtime : new GenerationException("Generation worker failed", error); }

    private record CollectionBlock(CollectionGenerationSpec spec, long end, ResolvedIdStrategy idStrategy,
                                   String batchUniquePath, int batchCapacity) { }
    private record BlockTotals(String collection,long generated,long written,long generatedBytes){}
    private record Block(List<BlockTotals> totals){}
}
