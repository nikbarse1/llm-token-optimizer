package com.example.demo;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TokenOptimizationService {

    private final TokenCounterService tokenCounterService;
    private final LLMSummarizationService llmSummarizationService;

    public TokenOptimizationService(TokenCounterService tokenCounterService, LLMSummarizationService llmSummarizationService) {
        this.tokenCounterService = tokenCounterService;
        this.llmSummarizationService = llmSummarizationService;
    }

    public Mono<OptimizationResponse> optimizeDocument(OptimizationRequest request) {
        String rawText = request.getDocument();
        int contextWindow = request.getContextWindow();

        // 1. Calculate original tokens
        int originalTokens = tokenCounterService.countTokens(rawText);

        // 2. Generate summary using LLM
        return llmSummarizationService.summarize(rawText)
                .map(summary -> {
                    int summaryTokens = tokenCounterService.countTokens(summary);

                    // 3. Compute metrics & headroom calculations
                    double reduction = 0.0;
                    if (originalTokens > 0) {
                        reduction = ((double) (originalTokens - summaryTokens) / originalTokens) * 100;
                    }

                    int headroomBefore = Math.max(0, contextWindow - originalTokens);
                    int headroomAfter = Math.max(0, contextWindow - summaryTokens);

                    // 4. Return complete analytics payload
                    return OptimizationResponse.builder()
                            .contextWindow(contextWindow)
                            .originalTokens(originalTokens)
                            .summaryTokens(summaryTokens)
                            .reductionPercentage(Double.parseDouble(String.format("%.2f", reduction)))
                            .headroomBefore(headroomBefore)
                            .headroomAfter(headroomAfter)
                            .summary(summary)
                            .build();
                });
    }
}
