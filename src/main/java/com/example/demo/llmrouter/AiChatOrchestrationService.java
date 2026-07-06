package com.example.demo.llmrouter;

import com.example.demo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatOrchestrationService {

    private final FileParserService fileParserService;
    private final WebScraperService webScraperService;
    private final TokenOptimizationService tokenOptimizationService;
    private final TokenCounterService tokenCounterService;
    private final PrimaryLlmService primaryLlmService;

    // Independent thresholds to prevent wasting API calls on tiny inputs
    private static final int INSTRUCTION_THRESHOLD_TOKENS = 100;
    private static final int DOCUMENT_THRESHOLD_TOKENS = 150;

    public Mono<AiChatResponse> processRequest(String instruction, MultipartFile file, String url, int contextWindow, boolean isDevMode) {

        return resolveDocumentContext(file, url).flatMap(contextResult -> {
            String docText = contextResult.text();
            String sourceType = contextResult.sourceType();

            // 1. Text-Only Shortcut: If no document/URL, don't run parallel tasks, just bypass.
            if ("TEXT_ONLY".equals(sourceType)) {
                log.info("Text-only request. Bypassing document optimization.");
                return processPath(instruction, INSTRUCTION_THRESHOLD_TOKENS, contextWindow, OptimizationRequest.TargetType.INSTRUCTION)
                        .flatMap(instOpt -> {

                            // Calculate savings for text-only path
                            String finalPrompt = instOpt.getSummary();
                            int finalPromptTokens = tokenCounterService.countTokens(finalPrompt);
                            int hypotheticalRawTokens = tokenCounterService.countTokens(instruction);
                            int turnTokensSaved = Math.max(0, hypotheticalRawTokens - finalPromptTokens);

                            OptimizationResponse metrics = mergeSingleMetrics(
                                    instOpt, contextWindow, finalPrompt,
                                    finalPromptTokens, hypotheticalRawTokens, turnTokensSaved
                            );

                            boolean wasOptimized = metrics.getTotalSavingsPercentage() > 0.0;

                            return primaryLlmService.askAi(finalPrompt)
                                    .map(aiAnswer -> buildResponse(aiAnswer, sourceType, wasOptimized, metrics, isDevMode));
                        });
            }

            log.info("Initiating Parallel Smart Optimization. Source: {}", sourceType);

            // 2. Setup Parallel Tasks
            Mono<OptimizationResponse> instructionPath = processPath(instruction, INSTRUCTION_THRESHOLD_TOKENS, contextWindow, OptimizationRequest.TargetType.INSTRUCTION);
            Mono<OptimizationResponse> documentPath = processPath(docText, DOCUMENT_THRESHOLD_TOKENS, contextWindow, OptimizationRequest.TargetType.DOCUMENT);

            // 3. Execute concurrently and wait for both to finish
            return Mono.zip(instructionPath, documentPath).flatMap(tuple -> {
                OptimizationResponse optimizedInst = tuple.getT1();
                OptimizationResponse optimizedDoc = tuple.getT2();

                // 4. Recombine logic & calculate exact token costs
                String finalPrompt = buildFinalPrompt(optimizedInst.getSummary(), optimizedDoc.getSummary());
                int finalPromptTokens = tokenCounterService.countTokens(finalPrompt);

                String naivePrompt = buildFinalPrompt(instruction, docText);
                int hypotheticalRawTokens = tokenCounterService.countTokens(naivePrompt);
                int turnTokensSaved = Math.max(0, hypotheticalRawTokens - finalPromptTokens);

                OptimizationResponse mergedMetrics = mergeMetrics(
                        optimizedInst, optimizedDoc, contextWindow,
                        finalPrompt, finalPromptTokens,
                        hypotheticalRawTokens, turnTokensSaved
                );

                boolean wasOptimized = mergedMetrics.getTotalSavingsPercentage() > 0.0;

                log.info("Parallel optimization complete. Sending combined payload to primary LLM.");

                // 5. Send to Gemini
                return primaryLlmService.askAi(finalPrompt)
                        .map(aiAnswer -> buildResponse(aiAnswer, sourceType, wasOptimized, mergedMetrics, isDevMode));
            });
        });
    }

    private Mono<OptimizationResponse> processPath(String text, int threshold, int contextWindow, OptimizationRequest.TargetType type) {
        if (text == null || text.isBlank()) {
            return Mono.just(createBypassMetrics("", 0));
        }

        int tokens = tokenCounterService.countTokens(text);
        if (tokens < threshold) {
            log.info("[{}] Tokens ({}) below threshold ({}). Bypassing Groq.", type, tokens, threshold);
            return Mono.just(createBypassMetrics(text, tokens));
        }

        log.info("[{}] Tokens ({}) above threshold. Sending to Groq.", type, tokens);
        OptimizationRequest request = new OptimizationRequest();
        request.setDocument(text);
        request.setContextWindow(contextWindow);
        request.setTargetType(type);

        return tokenOptimizationService.optimizeDocument(request);
    }

    private OptimizationResponse mergeMetrics(OptimizationResponse inst, OptimizationResponse doc, int contextWindow,
                                              String finalPromptContent, int finalPromptTokens,
                                              int hypotheticalRawTokens, int turnTokensSaved) {

        // 1. Internal Groq Math
        int groqInput = (inst.getGroqInputTokens() != null ? inst.getGroqInputTokens() : 0)
                + (doc.getGroqInputTokens() != null ? doc.getGroqInputTokens() : 0);
        int groqOutput = (inst.getGroqOutputTokens() != null ? inst.getGroqOutputTokens() : 0)
                + (doc.getGroqOutputTokens() != null ? doc.getGroqOutputTokens() : 0);
        double groqReduction = groqInput > 0 ? ((double) (groqInput - groqOutput) / groqInput) * 100 : 0.0;

        // 2. Total Savings Math
        double totalSavingsPercent = hypotheticalRawTokens > 0
                ? ((double) turnTokensSaved / hypotheticalRawTokens) * 100
                : 0.0;

        return OptimizationResponse.builder()
                .hypotheticalRawTokens(hypotheticalRawTokens)
                .finalPromptTokens(finalPromptTokens)
                .turnTokensSaved(turnTokensSaved)
                .totalSavingsPercentage(Double.parseDouble(String.format("%.2f", totalSavingsPercent)))
                .groqInputTokens(groqInput)
                .groqOutputTokens(groqOutput)
                .groqReductionPercentage(Double.parseDouble(String.format("%.2f", groqReduction)))
                .summary(buildFinalPromptSummary(inst.getSummary(), doc.getSummary()))
                .contextWindow(contextWindow)
                .headroomBefore(Math.max(0, contextWindow - hypotheticalRawTokens))
                .headroomAfter(Math.max(0, contextWindow - finalPromptTokens))
                .finalPromptContent(finalPromptContent)
                .build();
    }

    private OptimizationResponse mergeSingleMetrics(OptimizationResponse inst, int contextWindow,
                                                    String finalPromptContent, int finalPromptTokens,
                                                    int hypotheticalRawTokens, int turnTokensSaved) {

        int groqInput = inst.getGroqInputTokens() != null ? inst.getGroqInputTokens() : 0;
        int groqOutput = inst.getGroqOutputTokens() != null ? inst.getGroqOutputTokens() : 0;
        double groqReduction = groqInput > 0 ? ((double) (groqInput - groqOutput) / groqInput) * 100 : 0.0;

        double totalSavingsPercent = hypotheticalRawTokens > 0
                ? ((double) turnTokensSaved / hypotheticalRawTokens) * 100
                : 0.0;

        return OptimizationResponse.builder()
                .hypotheticalRawTokens(hypotheticalRawTokens)
                .finalPromptTokens(finalPromptTokens)
                .turnTokensSaved(turnTokensSaved)
                .totalSavingsPercentage(Double.parseDouble(String.format("%.2f", totalSavingsPercent)))
                .groqInputTokens(groqInput)
                .groqOutputTokens(groqOutput)
                .groqReductionPercentage(Double.parseDouble(String.format("%.2f", groqReduction)))
                .summary(inst.getSummary())
                .contextWindow(contextWindow)
                .headroomBefore(Math.max(0, contextWindow - hypotheticalRawTokens))
                .headroomAfter(Math.max(0, contextWindow - finalPromptTokens))
                .finalPromptContent(finalPromptContent)
                .build();
    }

    private OptimizationResponse createBypassMetrics(String text, int tokens) {
        return OptimizationResponse.builder()
                .groqInputTokens(tokens)
                .groqOutputTokens(tokens)
                .groqReductionPercentage(0.0)
                .summary(text)
                .build();
    }

    private String buildFinalPrompt(String optimizedInstruction, String optimizedDocument) {
        if (optimizedDocument == null || optimizedDocument.isBlank()) {
            return optimizedInstruction;
        }
        return String.format("User Instruction:\n%s\n\n--- Document Context ---\n%s", optimizedInstruction, optimizedDocument);
    }

    private String buildFinalPromptSummary(String optimizedInstruction, String optimizedDocument) {
        return String.format("Instruction Snapshot:\n%s\n\nDocument Snapshot:\n%s", optimizedInstruction, optimizedDocument);
    }

    private AiChatResponse buildResponse(String aiAnswer, String sourceType, boolean wasOptimized, OptimizationResponse metrics, boolean isDevMode) {
        return AiChatResponse.builder()
                .userReadableMessage(aiAnswer)
                .sourceType(sourceType)
                .wasOptimized(wasOptimized)
                .optimizationMetrics((isDevMode && wasOptimized) ? metrics : null)
                .build();
    }

    private Mono<ContextResult> resolveDocumentContext(MultipartFile file, String url) {
        if (file != null && !file.isEmpty()) {
            return Mono.fromCallable(() -> fileParserService.extractText(file))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(text -> new ContextResult(text, "FILE"))
                    .onErrorResume(e -> Mono.just(new ContextResult("", "FILE_ERROR")));
        }
        if (url != null && !url.isBlank()) {
            return webScraperService.scrapeUrl(url)
                    .map(result -> new ContextResult(result.content(), "URL"));
        }
        return Mono.just(new ContextResult("", "TEXT_ONLY"));
    }

    private record ContextResult(String text, String sourceType) {}
}