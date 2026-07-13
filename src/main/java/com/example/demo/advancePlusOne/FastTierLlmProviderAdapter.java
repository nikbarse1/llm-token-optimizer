package com.example.demo.advancePlusOne;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class FastTierLlmProviderAdapter implements LlmProvider {

    private final WebClient webClient;
    private final String activeModel;

    public FastTierLlmProviderAdapter(
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
    }

    @Override
    public String getProviderName() {
        return "FAST_TIER";
    }

    @Override
    public Mono<String> askAi(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.just("Empty prompt provided.");
        }

        log.info("Routing payload to Fast-Tier adapter using model: {}", activeModel);

        Map<String, Object> requestBody = Map.of(
                "model", activeModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.7,
                "max_tokens", 2048
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

                                        FastTierChatResponse chatResponse = mapper.readValue(responseBody, FastTierChatResponse.class);

                                        if (chatResponse != null && chatResponse.getChoices() != null && !chatResponse.getChoices().isEmpty()) {
                                            return Mono.just(chatResponse.getChoices().get(0).getMessage().getContent());
                                        }
                                        return Mono.just("Error: Received empty choices block from the fast-tier API.");
                                    } catch (Exception e) {
                                        log.error("Failed parsing fast-tier response payload", e);
                                        return Mono.error(e);
                                    }
                                });
                    } else {
                        return response.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("Fast-Tier Gateway Error - Status: {}, Body: {}", response.statusCode(), errorBody);
                                    return Mono.just("Error communication with fast-tier API: " + response.statusCode());
                                });
                    }
                })
                .onErrorResume(e -> {
                    log.error("Fatal failure downstream inside fast-tier integration loop: {}", e.getMessage());
                    return Mono.just("Fallback failure handling active turn logic.");
                });
    }

    @Data
    private static class FastTierChatResponse {
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