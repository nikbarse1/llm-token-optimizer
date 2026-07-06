package com.example.demo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LLMSummarizationService {

    private static final int CHUNK_CHAR_SIZE = 20_000;
    private static final int CHUNK_CONCURRENCY = 3;
    private static final int GENEROUS_MAX_TOKENS = 4000; // Let the LLM decide, just set a safe upper ceiling

    private final WebClient webClient;
    private final String model;

    public LLMSummarizationService(
            @Value("${llm.api.key:}") String apiKey,
            @Value("${llm.model:llama-3.3-70b-versatile}") String model
    ) {
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.groq.com/openai/v1/chat/completions")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    public Mono<String> smartCompress(String text, OptimizationRequest.TargetType targetType) {
        if (text == null || text.isBlank()) {
            return Mono.just("Empty input.");
        }

        List<String> chunks = splitIntoChunks(text, CHUNK_CHAR_SIZE);

        if (chunks.size() == 1) {
            return callGroq(buildSmartPrompt(chunks.get(0), targetType), GENEROUS_MAX_TOKENS, chunks.get(0));
        }

        log.info("Input is large ({} chars) - splitting into {} chunks for smart map-reduce",
                text.length(), chunks.size());

        // Map Step
        return Flux.fromIterable(chunks)
                .index()
                .flatMapSequential(indexed -> callGroq(
                        buildMapPrompt(indexed.getT2(), targetType, indexed.getT1().intValue() + 1, chunks.size()),
                        GENEROUS_MAX_TOKENS,
                        indexed.getT2()), CHUNK_CONCURRENCY)
                .collectList()
                .flatMap(extractedNotes -> {
                    // Reduce Step
                    String combinedNotes = String.join("\n\n", extractedNotes);
                    return callGroq(buildSmartPrompt(combinedNotes, targetType), GENEROUS_MAX_TOKENS, combinedNotes);
                });
    }

    private String getBaseRules() {
        return """
               CRITICAL RULES:
               1. DO NOT summarize, alter, or truncate any source code, JSON, XML, scripts, or configuration files. Preserve all syntax and code blocks verbatim.
               2. Remove filler words, conversational pleasantries, and redundant prose.
               3. Optimize for token efficiency while retaining 100% of the technical accuracy and factual data.
               """;
    }

    private String buildSmartPrompt(String text, OptimizationRequest.TargetType targetType) {
        if (targetType == OptimizationRequest.TargetType.INSTRUCTION) {
            return "You are an Expert Prompt Engineer. Your task is to refine and condense the following user instruction to be as concise as possible for another LLM, without losing ANY specific requirements, constraints, or context.\n\n"
                    + getBaseRules() + "\n\nUser Instruction to Optimize:\n" + text;
        } else {
            return "You are a Context Optimization Engine. Your task is to compress the following document. Retain all factual data, architectural decisions, parameters, and structural integrity, but aggressively compress the prose.\n\n"
                    + getBaseRules() + "\n\nDocument to Optimize:\n" + text;
        }
    }

    private String buildMapPrompt(String chunk, OptimizationRequest.TargetType targetType, int partIndex, int totalParts) {
        return "You are helping compress a large input (Part " + partIndex + " of " + totalParts + ").\n\n"
                + getBaseRules() + "\n\nExtract every important fact, decision, instruction, and ALL code verbatim as concise notes. Output ONLY the extracted notes:\n\n" + chunk;
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + chunkSize, length);
            if (end < length) {
                int breakPoint = text.lastIndexOf("\n\n", end);
                if (breakPoint > start + (chunkSize / 2)) {
                    end = breakPoint;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = end;
        }
        return chunks;
    }

    private Mono<String> callGroq(String prompt, int maxTokens, String fallbackSource) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.1, // Lower temperature for more deterministic/strict extraction
                "max_tokens", maxTokens
        );

        return webClient.post()
                .bodyValue(requestBody)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .flatMap(responseBody -> {
                                    try {
                                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                                        GroqResponse groqResponse = mapper.readValue(responseBody, GroqResponse.class);
                                        if (groqResponse != null && groqResponse.choices != null && !groqResponse.choices.isEmpty()) {
                                            return Mono.just(groqResponse.choices.get(0).message.content);
                                        }
                                        return Mono.just(fallbackSource); // Reverting to original source on failure to prevent data loss
                                    } catch (Exception e) {
                                        log.error("Error parsing Groq response: {}", responseBody);
                                        return Mono.just(fallbackSource);
                                    }
                                });
                    } else {
                        return response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("Groq API error status: {}, body: {}", response.statusCode(), errorBody);
                                    return Mono.just(fallbackSource);
                                })
                                .switchIfEmpty(Mono.fromRunnable(() -> log.error("Groq API error status: {}, no error body", response.statusCode()))
                                        .then(Mono.just(fallbackSource)));
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error calling Groq API: {}, using fallback", e.getMessage());
                    return Mono.just(fallbackSource);
                });
    }

    @Data
    private static class GroqResponse {
        @JsonProperty("choices")
        private List<GroqChoice> choices;
    }

    @Data
    private static class GroqChoice {
        @JsonProperty("message")
        private GroqMessage message;
    }

    @Data
    private static class GroqMessage {
        @JsonProperty("content")
        private String content;
    }
}