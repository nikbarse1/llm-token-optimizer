package com.example.demo.embeddings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GeminiEmbeddingService {

    private final WebClient webClient;
    private final String apiKey;
    private final String embeddingModel;

    public GeminiEmbeddingService(
            WebClient.Builder webClientBuilder,
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.embedding.model:gemini-embedding-2}") String embeddingModel) {

        // Pointing directly to the standard REST endpoint for Gemini Embeddings
        this.webClient = webClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/models")
                .build();
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Converts a raw text string into a dense mathematical vector.
     */
    public Mono<List<Double>> generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }

        Map<String, Object> requestBody = Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of(
                        "parts", List.of(
                                Map.of("text", text)
                        )
                )
        );

        return webClient.post()
                .uri("/{model}:embedContent?key={key}", embeddingModel, apiKey)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .map(response -> response.embedding().values())
                .doOnError(e -> log.error("Failed to generate vector embedding: {}", e.getMessage()));
    }

    // Lightweight records to easily deserialize the Gemini JSON response
    private record EmbeddingResponse(EmbeddingData embedding) {}
    private record EmbeddingData(List<Double> values) {}
}
