package com.example.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "Response containing token count information")
public class TokenResponse {
    
    @Schema(description = "Model used for tokenization", example = "gpt-4")
    private String modelUsed;
    
    @Schema(description = "Number of tokens in the text", example = "150")
    private int tokenCount;
    
    @Schema(description = "Number of characters in the text", example = "600")
    private int characterCount;
    
    @Schema(description = "Estimated cost information", example = "1 token is roughly 4 characters in English.")
    private String estimatedCostNote;
    
    @Schema(description = "Timestamp of the response")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
