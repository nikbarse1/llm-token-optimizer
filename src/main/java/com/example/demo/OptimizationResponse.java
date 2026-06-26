package com.example.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "Response containing document optimization results")
public class OptimizationResponse {
    
    @Schema(description = "Target context window size", example = "16000")
    private int contextWindow;
    
    @Schema(description = "Original token count", example = "5000")
    private int originalTokens;
    
    @Schema(description = "Token count after summarization", example = "500")
    private int summaryTokens;
    
    @Schema(description = "Percentage reduction in tokens", example = "90.0")
    private double reductionPercentage;
    
    @Schema(description = "Available tokens before optimization", example = "11000")
    private int headroomBefore;
    
    @Schema(description = "Available tokens after optimization", example = "15500")
    private int headroomAfter;
    
    @Schema(description = "Summarized document text")
    private String summary;
    
    @Schema(description = "Timestamp of the response")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
