package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts.IDocumentReindexContext;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RequiredArgsConstructor
public class DocumentReindexer {

    protected final OpenSearchClient client;
    private final int maxDocsPerBulkRequest;
    private final long maxBytesPerBulkRequest;
    private final int maxConcurrentWorkItems;
    private final IJsonTransformer transformer;

    public Mono<Void> reindex(String indexName, Flux<RfsLuceneDocument> documentStream, IDocumentReindexContext context) {
        var scheduler = Schedulers.newParallel("DocumentBulkAggregator");
        var bulkDocs = documentStream
            .publishOn(scheduler, 1)
            .map(doc -> transformDocument(doc,indexName));

        return this.reindexDocsInParallelBatches(bulkDocs, indexName, context)
            .doOnSuccess(unused -> log.debug("All batches processed"))
            .doOnError(e -> log.error("Error prevented all batches from being processed", e))
            .doOnTerminate(scheduler::dispose);
    }

    Mono<Void> reindexDocsInParallelBatches(Flux<BulkDocSection> docs, String indexName, IDocumentReindexContext context) {
        // Use parallel scheduler for send subscription due on non-blocking io client
        var scheduler = Schedulers.newParallel("DocumentBatchReindexer");
        var bulkDocsBatches = batchDocsBySizeOrCount(docs);
        var bulkDocsToBuffer = 50; // Arbitrary, takes up 500MB at default settings

        return bulkDocsBatches
            .limitRate(bulkDocsToBuffer, 1) // Bulk Doc Buffer, Keep Full
            .publishOn(scheduler, 1) // Switch scheduler
            .flatMap(docsGroup -> sendBulkRequest(UUID.randomUUID(), docsGroup, indexName, context, scheduler),
                maxConcurrentWorkItems)
            .doOnTerminate(scheduler::dispose)
            .then();
    }

    @SneakyThrows
    BulkDocSection transformDocument(RfsLuceneDocument doc, String indexName) {
        var original = new BulkDocSection(doc.id, indexName, doc.type, doc.source);
        if (transformer != null) {
            final Map<String,Object> transformedDoc = transformer.transformJson(original.toMap());
            return BulkDocSection.fromMap(transformedDoc);
        }
        return BulkDocSection.fromMap(original.toMap());
    }

    Mono<Void> sendBulkRequest(UUID batchId, List<BulkDocSection> docsBatch, String indexName, IDocumentReindexContext context, Scheduler scheduler) {
        return client.sendBulkRequest(indexName, docsBatch, context.createBulkRequest()) // Send the request
            .doFirst(() -> log.atInfo().log("Batch Id:{}, {} documents in current bulk request.", batchId, docsBatch.size()))
            .doOnSuccess(unused -> log.atDebug().log("Batch Id:{}, succeeded", batchId))
            .doOnError(error -> log.atError().log("Batch Id:{}, failed {}", batchId, error.getMessage()))
            // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
            .onErrorResume(e -> Mono.empty())
            .then() // Discard the response object
            .subscribeOn(scheduler);
    }

    Flux<List<BulkDocSection>> batchDocsBySizeOrCount(Flux<BulkDocSection> docs) {
        return docs.bufferUntil(new Predicate<>() {
            private int currentItemCount = 0;
            private long currentSize = 0;

            @Override
            public boolean test(BulkDocSection next) {
                // Add one for newline between bulk sections
                var nextSize = next.asString().length() + 1L;
                currentSize += nextSize;
                currentItemCount++;

                if (currentItemCount > maxDocsPerBulkRequest || currentSize > maxBytesPerBulkRequest) {
                // Reset and return true to signal to stop buffering.
                // Current item is included in the current buffer
                currentItemCount = 1;
                currentSize = nextSize;
                return true;
                }
                return false;
            }
        }, true);
    }

}
