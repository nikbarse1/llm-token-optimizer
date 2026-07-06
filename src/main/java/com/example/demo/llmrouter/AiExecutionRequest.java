package com.example.demo.llmrouter;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.codec.multipart.FilePart;

@Data
@Builder
@Schema(description = "Request wrapper for executing optimized AI prompts with contextual data")
public class AiExecutionRequest {

    @Schema(description = "The core instruction or question for the AI", example = "Summarize the key findings from this document.")
    private String instruction;

    @Schema(description = "An optional public URL containing text data to pull context from", example = "https://example.com/article")
    private String url;

    @Schema(description = "The context window size in tokens", defaultValue = "8192")
    private Integer contextWindow;
}
