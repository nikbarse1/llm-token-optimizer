package com.example.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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

    // --- 1. THE SAVINGS DASHBOARD (What you want to see for demos) ---

    @Schema(description = "If we didn't use the Gateway, Gemini would have charged you for this many tokens.")
    private Integer hypotheticalRawTokens;

    @Schema(description = "Because we used the Gateway, Gemini only charged you for this many tokens.")
    private Integer finalPromptTokens;

    @Schema(description = "The exact number of tokens the Gateway prevented Gemini from billing you for.")
    private Integer turnTokensSaved;

    @Schema(description = "Overall percentage of tokens saved on this request.")
    private Double totalSavingsPercentage;


    // --- 2. INTERNAL METRICS (Groq's job, kept for debugging) ---

    @Schema(description = "The text Groq actually compressed.")
    private String summary;

    @Schema(description = "Tokens sent to Groq for compression.")
    private Integer groqInputTokens;

    @Schema(description = "Tokens returned by Groq after compression.")
    private Integer groqOutputTokens;

    @Schema(description = "How much Groq reduced its specific chunk.")
    private Double groqReductionPercentage;


    // --- 3. CONTEXT TRACKING (Optional, kept from original) ---
    private Integer contextWindow;
    private Integer headroomBefore;
    private Integer headroomAfter;

    // --- 4. THE PAYLOAD (What was actually sent) ---
    @Schema(description = "The exact verbatim text payload sent to Gemini")
    private String finalPromptContent;
}