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

/**
 * Compresses arbitrarily large text down to a target token budget using an LLM.
 * Large documents are split into chunks, each chunk's key information is extracted
 * (map step), and the extracted notes are then compressed into a single, structured,
 * problem-solving-oriented result (reduce step). This ensures content beyond a single
 * LLM call's size limit is never silently dropped.
 */
@Service
@Slf4j
public class LLMSummarizationService {

    // Each chunk is kept small enough to safely fit in one LLM call (~5k tokens).
    private static final int CHUNK_CHAR_SIZE = 20_000;
    private static final int MAP_STEP_MAX_TOKENS = 600;
    private static final int CHUNK_CONCURRENCY = 3;

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

    /**
     * Compresses the given text down to approximately targetTokens tokens.
     * Handles documents of any size by chunking + map-reduce summarization.
     */
    public Mono<String> compress(String text, int targetTokens) {
        if (text == null || text.isBlank()) {
            return Mono.just("Empty document.");
        }

        List<String> chunks = splitIntoChunks(text, CHUNK_CHAR_SIZE);

        if (chunks.size() == 1) {
            return callGroq(buildCompressionPrompt(chunks.get(0), targetTokens), targetTokens, chunks.get(0));
        }

        log.info("Document is large ({} chars) - splitting into {} chunks for map-reduce compression",
                text.length(), chunks.size());

        return Flux.fromIterable(chunks)
                .index()
                .flatMapSequential(indexed -> callGroq(
                        buildExtractionPrompt(indexed.getT2(), indexed.getT1().intValue() + 1, chunks.size()),
                        MAP_STEP_MAX_TOKENS,
                        indexed.getT2()), CHUNK_CONCURRENCY)
                .collectList()
                .flatMap(extractedNotes -> {
                    String combinedNotes = String.join("\n\n", extractedNotes);
                    return callGroq(buildCompressionPrompt(combinedNotes, targetTokens), targetTokens, combinedNotes);
                });
    }

    /**
     * Splits text into chunks, preferring paragraph boundaries so sentences/code blocks
     * are not cut in half.
     */
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

    private String buildExtractionPrompt(String chunk, int partIndex, int totalParts) {
        return "You are helping compress a large document so it fits into an LLM's context window " +
                "while remaining genuinely useful for answering questions and solving problems about it.\n\n" +
                "From part " + partIndex + " of " + totalParts + " below, extract every important fact, " +
                "decision, instruction, number, and technical/code detail as concise notes. " +
                "Do not add an introduction or commentary - output only the extracted notes:\n\n" + chunk;
    }

    private String buildCompressionPrompt(String text, int targetTokens) {
        return "Compress the following content into approximately " + targetTokens + " tokens while keeping it " +
                "genuinely useful for understanding and solving problems related to it.\n" +
                "Requirements:\n" +
                "- Preserve all critical facts, numbers, decisions, and technical/code details\n" +
                "- Use clear structure (short paragraphs or bullet points), not a single dense block\n" +
                "- Remove filler, repetition, and boilerplate\n" +
                "- The result must be understandable on its own, without needing the original document\n\n" +
                "Content:\n" + text;
    }

    private Mono<String> callGroq(String prompt, int maxTokens, String fallbackSource) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", prompt
                        )
                ),
                "temperature", 0.3,
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
                                        return Mono.just(localSummarize(fallbackSource));
                                    } catch (Exception e) {
                                        log.error("Error parsing Groq response: {}", responseBody);
                                        return Mono.just(localSummarize(fallbackSource));
                                    }
                                });
                    } else {
                        return response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("Groq API error status: {}, body: {}", response.statusCode(), errorBody);
                                    return Mono.just(localSummarize(fallbackSource));
                                })
                                .switchIfEmpty(Mono.fromRunnable(() ->
                                    log.error("Groq API error status: {}, no error body", response.statusCode())
                                ).then(Mono.just(localSummarize(fallbackSource))));
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error calling Groq API: {}, using local fallback", e.getMessage());
                    return Mono.just(localSummarize(fallbackSource));
                });
    }

    private String localSummarize(String text) {
        if (text == null || text.isBlank()) {
            return "Empty Document.";
        }

        // Extractive summarization: Score sentences by importance
        String[] sentences = text.split("[.!?\\n]+");
        List<String> nonEmptySentences = Arrays.stream(sentences)
                .map(String::trim)
                .filter(s -> s.length() > 5)
                .collect(Collectors.toList());

        if (nonEmptySentences.isEmpty()) {
            return text;
        }

        // Score sentences based on length and keyword presence
        List<ScoredSentence> scored = nonEmptySentences.stream()
                .map(sentence -> new ScoredSentence(sentence, scoreSentence(sentence)))
                .sorted(Comparator.comparingDouble(ScoredSentence::getScore).reversed())
                .collect(Collectors.toList());

        int summarySize = Math.max(5, (int) (nonEmptySentences.size() * 0.2));
        summarySize = Math.min(summarySize, scored.size());
        List<String> topSentences = scored.subList(0, summarySize).stream()
                .map(ScoredSentence::getSentence)
                .collect(Collectors.toList());

        // Reorder to maintain original flow
        List<String> orderedSummary = nonEmptySentences.stream()
                .filter(topSentences::contains)
                .collect(Collectors.toList());

        return "Summary (Local Fallback):\n" + String.join(". ", orderedSummary) + ".";
    }

    private double scoreSentence(String sentence) {
        double score = 0.0;
        String lower = sentence.toLowerCase();

        // Prefer longer sentences (but not too long)
        int length = sentence.length();
        if (length > 20 && length < 200) {
            score += 10;
        }

        // Bonus for important keywords
        String[] keywords = {"deploy", "security", "scan", "build", "test", "check", "run", "setup", "install", "health", "success", "error", "fail", "pass", "job", "step", "workflow", "action", "docker", "container", "service", "api", "backend", "frontend"};
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                score += 5;
            }
        }

        // Bonus for sentences with numbers
        if (sentence.matches(".*\\d+.*")) {
            score += 3;
        }

        // Penalty for very short sentences
        if (length < 15) {
            score -= 5;
        }

        return score;
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

    @Data
    private static class ScoredSentence {
        private final String sentence;
        private final double score;
    }
}
