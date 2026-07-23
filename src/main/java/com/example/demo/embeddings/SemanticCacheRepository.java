package com.example.demo.embeddings;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

@Repository
@Slf4j
public class SemanticCacheRepository {

    private final JedisPooled jedis;
    private final double similarityThreshold;

    private static final String INDEX_NAME = "idx:semantic_cache";
    private static final String PREFIX = "semantic_cache:";

    public SemanticCacheRepository(
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${semantic.cache.similarity.threshold:0.95}") double similarityThreshold) {

        // JedisPooled exposes both the standard Redis commands and the powerful FT.* search APIs
        this.jedis = new JedisPooled(redisHost, redisPort);
        this.similarityThreshold = similarityThreshold;
    }

    @PostConstruct
    public void initIndex() {
        try {

            Schema schema = new Schema();

            schema.addTextField("instruction", 1.0);
            schema.addTextField("response", 1.0);

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
                    .setPrefixes(new String[]{PREFIX});

            jedis.ftCreate(
                    INDEX_NAME,
                    IndexOptions.defaultOptions().setDefinition(definition),
                    schema
            );

            log.info("Created RediSearch index {}", INDEX_NAME);

        } catch (JedisDataException e) {

            if (e.getMessage().contains("Index already exists")) {
                log.info("Index already exists.");
            } else {
                throw e;
            }
        }
    }

    /**
     * Stores the user's instruction, the AI's response, and the mathematical vector in Redis.
     */
    public void cacheResponse(String instruction,
                              String response,
                              List<Double> embedding) {

        String key = PREFIX + UUID.randomUUID();

        Map<String, String> map = new HashMap<>();

        map.put("instruction", instruction);
        map.put("response", response);

        jedis.hset(key.getBytes(), Map.of(
                "instruction".getBytes(), instruction.getBytes(),
                "response".getBytes(), response.getBytes(),
                "embedding".getBytes(), floatArrayToByteArray(embedding)
        ));
    }

    /**
     * Searches Redis for a semantically similar previous instruction.
     */
    public String findCachedResponse(List<Double> embedding) {

        double maxDistance = 1 - similarityThreshold;

        Query query = new Query("*=>[KNN 1 @embedding $vec AS distance]")
                .addParam("vec", floatArrayToByteArray(embedding))
                .returnFields("response", "distance")
                .setSortBy("distance", true)
                .dialect(2);

        SearchResult result = jedis.ftSearch(INDEX_NAME, query);

        if (result.getTotalResults() == 0) {
            log.info("Semantic Cache MISS.");
            return null;
        }

        Document doc = result.getDocuments().get(0);

        double distance = Double.parseDouble(doc.getString("distance"));

        if (distance <= maxDistance) {

            log.info("Semantic Cache HIT. Distance={}", distance);

            return doc.getString("response");
        }

        log.info("Semantic Cache MISS.");

        return null;
    }

    /**
     * Redis requires vectors to be encoded as a ByteOrder.LITTLE_ENDIAN byte string.
     * This helper method converts the Gemini response into the correct binary format.
     */
    private byte[] floatArrayToByteArray(List<Double> vector) {
        byte[] bytes = new byte[Float.BYTES * vector.size()];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (Double val : vector) {
            buffer.putFloat(val.floatValue());
        }
        return bytes;
    }
}