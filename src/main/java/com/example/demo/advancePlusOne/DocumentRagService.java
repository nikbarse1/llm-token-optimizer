package com.example.demo.advancePlusOne;

import com.example.demo.embeddings.GeminiEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentRagService {

    private final GeminiEmbeddingService embeddingService;
    private final JedisPooled jedis;

    private static final String RAG_INDEX_NAME = "idx:rag_docs";
    private static final String RAG_PREFIX = "rag_doc:";

    public DocumentRagService(GeminiEmbeddingService embeddingService, JedisPooled jedis) {
        this.embeddingService = embeddingService;
        this.jedis = jedis;
        initRagIndex();
    }

    private void initRagIndex() {
        try {
            Schema schema = new Schema();
            schema.addTextField("content", 1.0);
            schema.addVectorField(
                    "embedding",
                    Schema.VectorField.VectorAlgo.HNSW,
                    Map.of(
                            "TYPE", "FLOAT32",
                            "DIM", 768,
                            "DISTANCE_METRIC", "COSINE"
                    )
            );
            IndexDefinition definition = new IndexDefinition(IndexDefinition.Type.HASH)
                    .setPrefixes(new String[]{RAG_PREFIX});

            jedis.ftCreate(
                    RAG_INDEX_NAME,
                    IndexOptions.defaultOptions().setDefinition(definition),
                    schema
            );
            log.info("Initialized Redis RAG Index: {}", RAG_INDEX_NAME);
        } catch (Exception e) {
            // Index likely already exists
            log.debug("RAG index check/creation notice: {}", e.getMessage());
        }
    }

    /**
     * Ingests a raw document text, chunks it, generates embeddings, and indexes them in Redis.
     */
    public Mono<Void> indexDocument(String chatId, String documentText) {
        if (documentText == null || documentText.isBlank()) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            // Clean up any previous chunks for this chat session
            flushSessionChunks(chatId);

            // Split document into logical paragraphs or line blocks
            List<String> chunks = Arrays.stream(documentText.split("\\n\\n"))
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());

            if (chunks.isEmpty()) {
                chunks = List.of(documentText); // Fallback if no double-newlines
            }

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                // Synchronously generate embedding for the chunk
                List<Double> embedding = embeddingService.generateEmbedding(chunk).block();

                if (embedding != null && !embedding.isEmpty()) {
                    String docKey = RAG_PREFIX + chatId + ":" + i;
                    jedis.hset(docKey.getBytes(), Map.of(
                            "content".getBytes(), chunk.getBytes(),
                            "embedding".getBytes(), floatArrayToByteArray(embedding)
                    ));
                }
            }
            log.info("Indexed {} chunks for RAG in session: {}", chunks.size(), chatId);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Performs a vector similarity search against the indexed document chunks,
     * returning only the top relevant context snippets for the user's instruction.
     */
    public Mono<String> retrieveRelevantContext(String chatId, String instruction, int topK) {
        return embeddingService.generateEmbedding(instruction)
                .flatMap(instructionEmbedding -> Mono.fromCallable(() -> {
                    if (instructionEmbedding == null || instructionEmbedding.isEmpty()) {
                        return "";
                    }

                    // Query Redis using KNN search restricted to this chatId's prefix space
                    String queryStr = String.format("@content:*=>[KNN %d @embedding $vec AS distance]", topK);
                    Query query = new Query(queryStr)
                            .addParam("vec", floatArrayToByteArray(instructionEmbedding))
                            .returnFields("content", "distance")
                            .setSortBy("distance", true)
                            .dialect(2);

                    SearchResult result = jedis.ftSearch(RAG_INDEX_NAME, query);
                    if (result.getTotalResults() == 0) {
                        log.debug("No RAG context found for chatId={}", chatId);
                        return "";
                    }

                    log.info("Retrieved {} RAG chunks for chatId={}", result.getTotalResults(), chatId);

                    // Collect and join the top relevant chunks
                    return result.getDocuments().stream()
                            .filter(doc -> doc.hasProperty("content"))
                            .map(doc -> doc.getString("content"))
                            .collect(Collectors.joining("\n\n--- [Retrieved Context Chunk] ---\n"));

                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.error("Failed to retrieve RAG context for chatId={}: {}", chatId, e.getMessage()));
    }

    public void flushSessionChunks(String chatId) {
        try {
            // Scan and delete keys matching rag_doc:chatId:*
            Set<String> keys = jedis.keys(RAG_PREFIX + chatId + ":*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        } catch (Exception e) {
            log.warn("Failed to flush RAG session chunks: {}", e.getMessage());
        }
    }

    private byte[] floatArrayToByteArray(List<Double> vector) {
        byte[] bytes = new byte[Float.BYTES * vector.size()];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (Double val : vector) {
            buffer.putFloat(val.floatValue());
        }
        return bytes;
    }
}