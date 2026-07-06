package com.example.demo.llmrouter;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import com.example.demo.OptimizationResponse;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiChatResponse {
    private String userReadableMessage;
    private OptimizationResponse optimizationMetrics;
    private String sourceType; // "FILE", "URL", or "TEXT_ONLY"
    private Boolean wasOptimized;
    private String chatId;
}
