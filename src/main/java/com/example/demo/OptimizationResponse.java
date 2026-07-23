package com.example.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptimizationResponse {

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    private RoutingDecision routingDecision;
    private UsageMetrics usageMetrics;
    private CompressionInternals compressionInternals;
    private PayloadSnapshot payloadSnapshot;

    // --- NESTED DTO CLASSES ---

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingDecision {
        private String requestedProvider;
        private String executedProvider;
        private String actionTaken;
    }

    // Replace the old BillingImpact class with this upgraded one
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageMetrics {
        private Integer expectedTokensBeforeOptimization; // What jtokkit estimated
        private Long actualPromptTokens;                  // True metric from API
        private Long actualCompletionTokens;              // True metric from API
        private Long actualTotalTokens;                   // True metric from API
        private Long tokensSaved;
        private Double savingsPercentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompressionInternals {
        private Integer tokensProcessed; // Fast-tier input
        private Integer tokensOutput;    // Fast-tier output
        private Double compressionReduction;
        private String compressionSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayloadSnapshot {
        private Integer contextWindowSize;
        private Integer remainingHeadroom;
        private String finalPrompt;
    }

    // --- TEMPORARY FIELDS USED DURING INTERNAL PIPELINE PROCESSING ---
    // (These are hidden from the final JSON output via @JsonIgnore if desired,
    // but kept here so your intermediate map-reduce logic doesn't break)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer tempFastTierInputTokens;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer tempFastTierOutputTokens;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String tempSummary;
}