package com.example.demo;

import com.example.dto.UnifiedAnalysisResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
@Slf4j
@Tag(name = "Unified Analysis", description = "Unified API for analyzing text, files, and URLs with token optimization")
public class UnifiedAnalysisController {

    private final ContentRouterService contentRouterService;
    private final TokenCounterService tokenCounterService;
    private final TokenOptimizationService optimizationService;

    public UnifiedAnalysisController(ContentRouterService contentRouterService,
                                   TokenCounterService tokenCounterService,
                                   TokenOptimizationService optimizationService) {
        this.contentRouterService = contentRouterService;
        this.tokenCounterService = tokenCounterService;
        this.optimizationService = optimizationService;
    }

    @PostMapping("/analyze")
    @Operation(summary = "Analyze content",
               description = "Analyze text, upload a file, or scrape a URL. The document is compressed only as " +
                       "much as needed to fit the given context window - the compression level is decided " +
                       "automatically, not chosen by the caller.")
    public Mono<ResponseEntity<UnifiedAnalysisResponse>> analyzeContent(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "contextWindow", defaultValue = "8192") int contextWindow) {

        log.info("=== UNIFIED ANALYSIS START ===");
        log.info("Input parameters - text: {}, file: {}, url: {}, contextWindow: {}",
                text != null ? "present" : "null",
                file != null ? file.getOriginalFilename() : "null",
                url,
                contextWindow);

        // Validate that at least one input is provided
        if ((text == null || text.isBlank()) && 
            (file == null || file.isEmpty()) && 
            (url == null || url.isBlank())) {
            log.error("No valid input provided");
            return Mono.just(ResponseEntity.badRequest()
                    .body(createErrorResponse("No valid input provided. Provide text, file, or URL.")));
        }

        // Extract content based on input type
        return contentRouterService.extractContent(text, file, url)
                .flatMap(extractionResult -> {
                    log.info("Content extracted successfully - type: {}, length: {} chars", 
                            extractionResult.inputType(), extractionResult.content().length());

                    if (extractionResult.content().isBlank()) {
                        log.error("Extracted content is empty");
                        return Mono.just(ResponseEntity.badRequest()
                                .body(createErrorResponse("No content could be extracted from the input.")));
                    }

                    // Calculate original tokens
                    int originalTokens = tokenCounterService.countTokens(extractionResult.content());
                    log.info("Original token count: {}", originalTokens);

                    // Create optimization request
                    OptimizationRequest request = new OptimizationRequest();
                    request.setDocument(extractionResult.content());
                    request.setContextWindow(contextWindow);

                    // Optimize the content
                    return optimizationService.optimizeDocument(request)
                            .map(optimizationResponse -> {
                                log.info("=== ANALYSIS COMPLETED ===");
                                log.info("Original tokens: {}, Optimized tokens: {}, Reduction: {}%",
                                        optimizationResponse.getOriginalTokens(),
                                        optimizationResponse.getSummaryTokens(),
                                        optimizationResponse.getReductionPercentage());

                                // Build unified response
                                UnifiedAnalysisResponse response = UnifiedAnalysisResponse.builder()
                                        .inputType(extractionResult.inputType())
                                        .optimizationLevel(UnifiedAnalysisResponse.OptimizationLevel
                                                .fromReductionPercentage(optimizationResponse.getReductionPercentage()))
                                        .source(getSource(extractionResult, text, file, url))
                                        .contentType(extractionResult.contentType())
                                        .extractionMethod(extractionResult.extractionMethod())
                                        .originalTokens(optimizationResponse.getOriginalTokens())
                                        .optimizedTokens(optimizationResponse.getSummaryTokens())
                                        .reductionPercentage(optimizationResponse.getReductionPercentage())
                                        .contextWindow(optimizationResponse.getContextWindow())
                                        .headroomBefore(optimizationResponse.getHeadroomBefore())
                                        .headroomAfter(optimizationResponse.getHeadroomAfter())
                                        .optimizedContent(optimizationResponse.getSummary())
                                        .originalContentPreview(getPreview(extractionResult.content()))
                                        .timestamp(LocalDateTime.now())
                                        .successful(true)
                                        .fileMetadata(extractionResult.fileMetadata())
                                        .urlMetadata(extractionResult.urlMetadata())
                                        .build();

                                return ResponseEntity.ok(response);
                            });
                })
                .onErrorResume(error -> {
                    log.error("Analysis failed: {}", error.getMessage(), error);
                    UnifiedAnalysisResponse errorResponse = createErrorResponse("Analysis failed: " + error.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }

    private String getSource(ContentRouterService.ContentExtractionResult extractionResult, 
                           String text, MultipartFile file, String url) {
        return switch (extractionResult.inputType()) {
            case TEXT -> "user-input";
            case FILE -> file != null ? file.getOriginalFilename() : "unknown-file";
            case URL -> url;
        };
    }

    private String getPreview(String content) {
        if (content == null || content.length() <= 200) {
            return content;
        }
        return content.substring(0, 200) + "...";
    }

    private UnifiedAnalysisResponse createErrorResponse(String errorMessage) {
        return UnifiedAnalysisResponse.builder()
                .inputType(UnifiedAnalysisResponse.InputType.TEXT)
                .optimizationLevel(UnifiedAnalysisResponse.OptimizationLevel.NONE)
                .originalTokens(0)
                .optimizedTokens(0)
                .reductionPercentage(0.0)
                .contextWindow(8192)
                .headroomBefore(8192)
                .headroomAfter(8192)
                .optimizedContent("")
                .originalContentPreview("")
                .timestamp(LocalDateTime.now())
                .successful(false)
                .errorMessage(errorMessage)
                .build();
    }
}
