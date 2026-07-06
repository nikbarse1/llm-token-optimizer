package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class TokenOptimizationService {

    private final TokenCounterService tokenCounterService;
    private final LLMSummarizationService llmSummarizationService;

    public TokenOptimizationService(TokenCounterService tokenCounterService, LLMSummarizationService llmSummarizationService) {
        this.tokenCounterService = tokenCounterService;
        this.llmSummarizationService = llmSummarizationService;
    }

    public Mono<OptimizationResponse> optimizeDocument(OptimizationRequest request) {
        String rawText = request.getDocument();
        int originalTokens = tokenCounterService.countTokens(rawText);

        log.info("Starting smart optimization for {} (Original Tokens: {})", request.getTargetType(), originalTokens);

        // Delegate completely to the LLM for smart compression
        return llmSummarizationService.smartCompress(rawText, request.getTargetType())
                .map(summary -> {
                    int summaryTokens = tokenCounterService.countTokens(summary);
                    log.info("Completed smart optimization for {}. New Tokens: {}", request.getTargetType(), summaryTokens);
                    return buildResponse(originalTokens, summaryTokens, summary);
                });
    }

    private OptimizationResponse buildResponse(int originalTokens, int summaryTokens, String content) {
        double reduction = 0.0;
        if (originalTokens > 0) {
            reduction = ((double) (originalTokens - summaryTokens) / originalTokens) * 100;
        }

        // Map directly to the new internal Groq fields in the updated DTO
        return OptimizationResponse.builder()
                .groqInputTokens(originalTokens)
                .groqOutputTokens(summaryTokens)
                .groqReductionPercentage(Double.parseDouble(String.format("%.2f", reduction)))
                .summary(content)
                .build();
    }
}