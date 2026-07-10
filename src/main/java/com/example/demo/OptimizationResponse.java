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
    private BillingImpact billingImpact;
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BillingImpact {
        private Integer baselineTokens; // What would have been charged
        private Integer billedTokens;   // What was actually charged
        private Integer tokensSaved;
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