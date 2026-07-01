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

    // Aggressive optimization: always compress documents to achieve 70-90% reduction
    private static final double TARGET_REDUCTION_RATIO = 0.8; // Target 80% reduction
    private static final int MIN_TARGET_TOKENS = 50; // Minimum tokens to preserve content
    private static final int MAX_TARGET_TOKENS = 200; // Maximum tokens for optimal LLM context

    public Mono<OptimizationResponse> optimizeDocument(OptimizationRequest request) {
        String rawText = request.getDocument();
        int contextWindow = request.getContextWindow();
        int originalTokens = tokenCounterService.countTokens(rawText);
        int targetTokens = computeTargetTokens(originalTokens);

        log.info("Optimization: {} original tokens -> target {} tokens ({}% reduction goal)", 
                originalTokens, targetTokens, (int)((1 - (double)targetTokens/originalTokens) * 100));

        // ALWAYS perform optimization - no more skipping for small documents

        return llmSummarizationService.compress(rawText, targetTokens)
                .map(summary -> {
                    int summaryTokens = tokenCounterService.countTokens(summary);
                    return buildResponse(contextWindow, originalTokens, summaryTokens, summary);
                });
    }

    /**
     * Computes target tokens for aggressive 70-90% reduction regardless of document size
     */
    private int computeTargetTokens(int originalTokens) {
        // Calculate target based on desired reduction ratio
        int target = (int) (originalTokens * (1 - TARGET_REDUCTION_RATIO));
        
        // Ensure we stay within reasonable bounds
        target = Math.max(MIN_TARGET_TOKENS, target);
        target = Math.min(MAX_TARGET_TOKENS, target);
        
        log.debug("Computed target tokens: {} (from {} original, {}% reduction)", 
                target, originalTokens, (int)(TARGET_REDUCTION_RATIO * 100));
        
        return target;
    }

    private OptimizationResponse buildResponse(int contextWindow, int originalTokens, int summaryTokens, String content) {
        double reduction = 0.0;
        if (originalTokens > 0) {
            reduction = ((double) (originalTokens - summaryTokens) / originalTokens) * 100;
        }

        int headroomBefore = Math.max(0, contextWindow - originalTokens);
        int headroomAfter = Math.max(0, contextWindow - summaryTokens);

        return OptimizationResponse.builder()
                .contextWindow(contextWindow)
                .originalTokens(originalTokens)
                .summaryTokens(summaryTokens)
                .reductionPercentage(Double.parseDouble(String.format("%.2f", reduction)))
                .headroomBefore(headroomBefore)
                .headroomAfter(headroomAfter)
                .summary(content)
                .build();
    }
}
