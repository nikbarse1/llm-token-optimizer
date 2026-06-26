package com.example.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request for counting tokens in text")
public class TokenRequest {
    
    @NotBlank(message = "Text is required")
    @Schema(description = "The text to count tokens for", example = "Hello, how are you today?")
    private String text;
    
    @Schema(description = "Model to use for tokenization", example = "gpt-4", defaultValue = "gpt-4")
    private String model = "gpt-4";
}
