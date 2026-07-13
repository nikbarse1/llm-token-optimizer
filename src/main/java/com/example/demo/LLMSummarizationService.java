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

@Service
@Slf4j
public class LLMSummarizationService {

    private static final int CHUNK_CHAR_SIZE = 12_000;
    private static final int CHUNK_CONCURRENCY = 1;
    private static final int SUMMARY_MAX_TOKENS = 1024;

    private final WebClient webClient;
    private final String activeModel;

    public LLMSummarizationService(
            @Value("${llm.fast_tier.base_url}") String baseUrl,
            @Value("${llm.fast_tier.api.key:}") String apiKey,
            @Value("${llm.fast_tier.model}") String activeModel
    ) {
        this.activeModel = activeModel;

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        log.info("Fast-Tier Engine configured using base URL: {}", baseUrl);
    }

    public Mono<String> smartCompress(String text, OptimizationRequest.TargetType targetType) {
        if (text == null || text.isBlank()) {
            return Mono.just("Empty input.");
        }

        List<String> chunks = splitIntoChunks(text, CHUNK_CHAR_SIZE);

        if (chunks.size() == 1) {
            return callFastTier(buildSmartPrompt(chunks.get(0), targetType), SUMMARY_MAX_TOKENS, chunks.get(0));
        }

        log.info("Input is large ({} chars) - splitting into {} chunks. Processing sequentially.", text.length(), chunks.size());

        return Flux.fromIterable(chunks)
                .index()
                .flatMapSequential(indexed -> callFastTier(
                        buildMapPrompt(indexed.getT2(), targetType, indexed.getT1().intValue() + 1, chunks.size()),
                        SUMMARY_MAX_TOKENS,
                        indexed.getT2()), CHUNK_CONCURRENCY)
                .collectList()
                .flatMap(extractedNotes -> {
                    String combinedNotes = String.join("\n\n", extractedNotes);
                    return callFastTier(buildSmartPrompt(combinedNotes, targetType), SUMMARY_MAX_TOKENS, combinedNotes);
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
            return "Refine and condense the following instruction. Preserve all requirements and constraints. Be concise.\n\n"
                    + getBaseRules() + "\n\nInput:\n" + text;
        } else {
            return "Compress the following document. Retain all factual data and parameters. Output strictly as bulleted notes.\n\n"
                    + getBaseRules() + "\n\nDocument:\n" + text;
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

    private Mono<String> callFastTier(String prompt, int maxTokens, String fallbackSource) {
        Map<String, Object> requestBody = Map.of(
                "model", activeModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.1,
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

                                        FastTierResponse fastTierResponse = mapper.readValue(responseBody, FastTierResponse.class);
                                        if (fastTierResponse != null && fastTierResponse.getChoices() != null && !fastTierResponse.getChoices().isEmpty()) {
                                            return Mono.just(fastTierResponse.getChoices().get(0).getMessage().getContent());
                                        }
                                        return Mono.just(fallbackSource);
                                    } catch (Exception e) {
                                        log.error("Error parsing Fast-Tier response: {}", responseBody);
                                        return Mono.just(fallbackSource);
                                    }
                                });
                    } else {
                        return response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("Fast-Tier API error status: {}, body: {}", response.statusCode(), errorBody);
                                    return Mono.just(fallbackSource);
                                })
                                .switchIfEmpty(Mono.fromRunnable(() -> log.error("Fast-Tier API error status: {}, no error body", response.statusCode()))
                                        .then(Mono.just(fallbackSource)));
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error calling Fast-Tier API: {}, using fallback", e.getMessage());
                    return Mono.just(fallbackSource);
                });
    }

    @Data
    private static class FastTierResponse {
        @JsonProperty("choices")
        private List<FastTierChoice> choices;
    }

    @Data
    private static class FastTierChoice {
        @JsonProperty("message")
        private FastTierMessage message;
    }

    @Data
    private static class FastTierMessage {
        @JsonProperty("content")
        private String content;
    }
}