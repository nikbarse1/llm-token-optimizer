package com.example.demo;

import com.example.demo.exception.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
@Slf4j
@Tag(name = "Document Optimization", description = "APIs for optimizing documents to fit within LLM context windows")
public class TokenOptimizationController {

    private final TokenOptimizationService optimizationService;

    public TokenOptimizationController(TokenOptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    @PostMapping("/optimize")
    @Operation(summary = "Optimize document", 
               description = "Summarizes a document to reduce token count while maintaining key information")
    public Mono<ResponseEntity<OptimizationResponse>> optimize(@Valid @RequestBody OptimizationRequest request) {
        log.info("Received optimization request for document of length: {}, context window: {}", 
                request.getDocument() != null ? request.getDocument().length() : 0,
                request.getContextWindow());
        
        if (request.getDocument() == null || request.getDocument().isBlank()) {
            throw new InvalidRequestException("Document field is required and cannot be empty");
        }

        return optimizationService.optimizeDocument(request)
                .doOnSuccess(response -> log.info("Optimization completed: {}% reduction, {} -> {} tokens",
                        response.getReductionPercentage(), 
                        response.getOriginalTokens(), 
                        response.getSummaryTokens()))
                .map(ResponseEntity::ok);
    }
}
