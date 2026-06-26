package com.example.demo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LLMSummarizationService {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    public LLMSummarizationService(
            @Value("${llm.api.key:}") String apiKey,
            @Value("${llm.model:llama-3.3-70b-versatile}") String model
    ) {
        this.apiKey = apiKey;
        this.model = model;
        
        this.webClient = WebClient.builder()
                .baseUrl("https://api.groq.com/openai/v1/chat/completions")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    public Mono<String> summarize(String text) {
        if (text == null || text.isBlank()) {
            return Mono.just("Empty Document.");
        }

        // Truncate text if too long
        String truncatedText = truncateText(text, 10000);
        return callGroq(truncatedText);
    }

    private Mono<String> callGroq(String text) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", "Extract ONLY critical facts as ultra-brief bullet points. Use minimal words, remove all filler, use abbreviations. Max 8 points:\n\n" + text
                        )
                ),
                "temperature", 0.1,
                "max_tokens", 120
        );

        return webClient.post()
                .bodyValue(requestBody)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(String.class)
                                .flatMap(responseBody -> {
                                    try {
                                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);                                        GroqResponse groqResponse = mapper.readValue(responseBody, GroqResponse.class);
                                        if (groqResponse != null && groqResponse.choices != null && !groqResponse.choices.isEmpty()) {
                                            return Mono.just(groqResponse.choices.get(0).message.content);
                                        }
                                        return Mono.just(localSummarize(text));
                                    } catch (Exception e) {
                                        log.error("Error parsing Groq response: {}", responseBody);
                                        return Mono.just(localSummarize(text));
                                    }
                                });
                    } else {
                        return response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("Groq API error status: {}, body: {}", response.statusCode(), errorBody);
                                    return Mono.just(localSummarize(text));
                                })
                                .switchIfEmpty(Mono.fromRunnable(() -> 
                                    log.error("Groq API error status: {}, no error body", response.statusCode())
                                ).then(Mono.just(localSummarize(text))));
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error calling Groq API: {}, using local fallback", e.getMessage());
                    log.error("Request body: {}", requestBody);
                    return Mono.just(localSummarize(text));
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

        // Take top 5-7 sentences for summary
        int summarySize = Math.min(7, scored.size());
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

    private String truncateText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
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
