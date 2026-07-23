package com.example.demo.advancePlusOne;

import com.example.demo.*;
import com.example.demo.OptimizationRequest.TargetType;
import com.example.demo.embeddings.GeminiEmbeddingService;
import com.example.demo.embeddings.SemanticCacheRepository;
import com.example.demo.llmrouter.AiChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdvancedGatewayOrchestrationService {

    private final ReactiveChatHistoryRepository historyRepository;
    private final LlmProviderRegistry providerRegistry;
    private final TokenOptimizationService tokenOptimizationService;
    private final TokenCounterService tokenCounterService;
    private final FileParserService fileParserService;
    private final WebScraperService webScraperService;
    private final LlmRouterService llmRouterService;

    // Injecting the Semantic Caching services
    private final GeminiEmbeddingService embeddingService;
    private final SemanticCacheRepository cacheRepository;

    private static final int SHORT_TERM_WINDOW_TURNS = 3;
    private static final int INSTRUCTION_THRESHOLD_TOKENS = 100;
    private static final int HISTORY_COMPRESSION_THRESHOLD = 200;

    public Mono<AiChatResponse> processStatefulChat(
            String instruction,
            MultipartFile file,
            String url,
            String chatId,
            String providerName,
            int contextWindow,
            boolean isDevMode
    ) {
        // --------------------------------------------------------------------
        // STEP A: SEMANTIC CACHE LOOKUP (0-TOKEN FAST PATH)
        // --------------------------------------------------------------------
        return embeddingService.generateEmbedding(instruction)
                .flatMap(embedding -> Mono.fromCallable(() -> cacheRepository.findCachedResponse(embedding))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(cachedAnswer -> {
                            if (cachedAnswer != null) {
                                log.info("⚡ Returning instant response from Semantic Cache for chatId: {}", chatId);
                                return handleCacheHit(instruction, cachedAnswer, chatId, isDevMode);
                            }
                            // Cache miss: execute full LLM pipeline and store result on completion
                            return executeFullLlmPipeline(
                                    instruction, file, url, chatId, providerName, contextWindow, isDevMode, embedding
                            );
                        })
                )
                // Fallback to standard pipeline if embedding generation fails
                .switchIfEmpty(executeFullLlmPipeline(
                        instruction, file, url, chatId, providerName, contextWindow, isDevMode, null
                ));
    }

    private Mono<AiChatResponse> executeFullLlmPipeline(
            String instruction,
            MultipartFile file,
            String url,
            String chatId,
            String providerName,
            int contextWindow,
            boolean isDevMode,
            List<Double> instructionEmbedding
    ) {
        Mono<ContextResult> contextResultMono = resolveDocumentContext(file, url);
        Mono<ChatSessionState> sessionStateMono = historyRepository.findByChatId(chatId);

        return Mono.zip(contextResultMono, sessionStateMono).flatMap(tuple -> {
            ContextResult contextResult = tuple.getT1();
            ChatSessionState sessionState = tuple.getT2();

            String augmentedInstruction = buildCombinedPrompt(instruction, contextResult.text());
            int instructionTokens = tokenCounterService.countTokens(augmentedInstruction);

            List<GatewayMessage> historicalChain = sessionState.getMessages();
            int splitIndex = Math.max(0, historicalChain.size() - SHORT_TERM_WINDOW_TURNS);

            List<GatewayMessage> distantHistory = historicalChain.subList(0, splitIndex);
            List<GatewayMessage> rawShortTermHistory = historicalChain.subList(splitIndex, historicalChain.size());

            String newDistantTurnsBlock = distantHistory.stream()
                    .map(msg -> String.format("%s: %s", msg.getRole(), msg.getContent()))
                    .collect(Collectors.joining("\n"));

            Mono<OptimizationResponse> instructionTrack = processOptimization(
                    augmentedInstruction, instructionTokens, INSTRUCTION_THRESHOLD_TOKENS, contextWindow, TargetType.INSTRUCTION
            );

            int estimatedHistoryTokens = tokenCounterService.countTokens(newDistantTurnsBlock)
                    + tokenCounterService.countTokens(sessionState.getCompressedDistantHistory());

            int dynamicHistoryThreshold = ((instructionTokens + estimatedHistoryTokens) > (contextWindow * 0.8))
                    ? HISTORY_COMPRESSION_THRESHOLD
                    : Integer.MAX_VALUE;

            Mono<OptimizationResponse> historyTrack = processHistoryCompounding(
                    newDistantTurnsBlock, sessionState.getCompressedDistantHistory(), contextWindow, dynamicHistoryThreshold
            );

            return Mono.zip(instructionTrack, historyTrack).flatMap(optTuple -> {
                OptimizationResponse optimizedInst = optTuple.getT1();
                OptimizationResponse optimizedHist = optTuple.getT2();

                sessionState.setCompressedDistantHistory(optimizedHist.getTempSummary());

                String compiledPrompt = stitchGatewayPayload(
                        optimizedInst.getTempSummary(),
                        optimizedHist.getTempSummary(),
                        rawShortTermHistory
                );

                int heuristicPromptTokens = tokenCounterService.countTokens(compiledPrompt);
                String naivePayload = stitchNaivePayload(instruction, historicalChain);

                ProviderRoutingContext routingContext = ProviderRoutingContext.builder()
                        .requestedProvider(instruction)
                        .instructionTokens(instructionTokens)
                        .finalPromptTokens(heuristicPromptTokens)
                        .hasHeavyContext(contextResult.text() != null && !contextResult.text().isBlank())
                        .build();

                String smartProviderName = llmRouterService.route(routingContext);

                int actualFinalTokens = tokenCounterService.countTokens(compiledPrompt, smartProviderName);
                int actualHypotheticalTokens = tokenCounterService.countTokens(naivePayload, smartProviderName);
                int turnTokensSaved = Math.max(0, actualHypotheticalTokens - actualFinalTokens);

                LlmProvider targetLlm = providerRegistry.getProvider(smartProviderName);

                return targetLlm.askAi(compiledPrompt).flatMap(aiAnswer -> {

                    // Save to Redis Cache in background if embedding is available
                    if (instructionEmbedding != null && !instructionEmbedding.isEmpty()) {
                        Mono.fromRunnable(() -> cacheRepository.cacheResponse(instruction, aiAnswer, instructionEmbedding))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();
                    }

                    String contentToSave = (contextResult.text() != null && !contextResult.text().isBlank())
                            ? instruction + "\n\n[System Note: User provided a document. Extracted Context: " + optimizedInst.getTempSummary() + "]"
                            : instruction;

                    GatewayMessage userTurn = GatewayMessage.builder()
                            .role(GatewayMessage.Role.USER)
                            .content(contentToSave)
                            .tokenCount(tokenCounterService.countTokens(contentToSave))
                            .timestamp(Instant.now())
                            .build();

                    GatewayMessage assistantTurn = GatewayMessage.builder()
                            .role(GatewayMessage.Role.ASSISTANT)
                            .content(aiAnswer)
                            .tokenCount(tokenCounterService.countTokens(aiAnswer))
                            .timestamp(Instant.now())
                            .build();

                    sessionState.addMessage(userTurn);
                    sessionState.addMessage(assistantTurn);

                    return historyRepository.save(sessionState).map(savedState -> {

                        OptimizationResponse mergedMetrics = mergeMetrics(
                                optimizedInst, optimizedHist, contextWindow,
                                compiledPrompt, actualFinalTokens,
                                actualHypotheticalTokens, turnTokensSaved,
                                smartProviderName,
                                providerName
                        );

                        return AiChatResponse.builder()
                                .userReadableMessage(aiAnswer)
                                .sourceType(contextResult.sourceType())
                                .wasOptimized(true)
                                .optimizationMetrics((isDevMode) ? mergedMetrics : null)
                                .chatId(chatId)
                                .build();
                    });
                });
            });
        });
    }

    private Mono<AiChatResponse> handleCacheHit(String instruction, String cachedAnswer, String chatId, boolean isDevMode) {
        return historyRepository.findByChatId(chatId).flatMap(sessionState -> {
            GatewayMessage userTurn = GatewayMessage.builder()
                    .role(GatewayMessage.Role.USER)
                    .content(instruction)
                    .tokenCount(tokenCounterService.countTokens(instruction))
                    .timestamp(Instant.now())
                    .build();

            GatewayMessage assistantTurn = GatewayMessage.builder()
                    .role(GatewayMessage.Role.ASSISTANT)
                    .content(cachedAnswer)
                    .tokenCount(tokenCounterService.countTokens(cachedAnswer))
                    .timestamp(Instant.now())
                    .build();

            sessionState.addMessage(userTurn);
            sessionState.addMessage(assistantTurn);

            return historyRepository.save(sessionState).map(savedState ->

                    AiChatResponse.builder()
                            .userReadableMessage(cachedAnswer)
                            .sourceType("SEMANTIC_CACHE_HIT")
                            .wasOptimized(true)
                            .optimizationMetrics(isDevMode ? createCacheHitMetrics() : null)
                            .chatId(chatId)
                            .build()
            );
        });
    }

    private OptimizationResponse createCacheHitMetrics() {
        return OptimizationResponse.builder()
                .routingDecision(OptimizationResponse.RoutingDecision.builder()
                        .requestedProvider("NONE")
                        .executedProvider("REDIS_SEMANTIC_CACHE")
                        .actionTaken("ZERO_TOKEN_CACHE_HIT")
                        .build())
                .billingImpact(OptimizationResponse.BillingImpact.builder()
                        .baselineTokens(0)
                        .billedTokens(0)
                        .tokensSaved(0)
                        .savingsPercentage(100.00)
                        .build())
                .build();
    }

    private Mono<OptimizationResponse> processOptimization(String text, int tokens, int threshold, int contextWindow, TargetType type) {
        if (text == null || text.isBlank()) {
            return Mono.just(createBypassMetrics("", 0));
        }
        if (tokens < threshold) {
            return Mono.just(createBypassMetrics(text, tokens));
        }
        OptimizationRequest request = new OptimizationRequest();
        request.setDocument(text);
        request.setContextWindow(contextWindow);
        request.setTargetType(type);
        return tokenOptimizationService.optimizeDocument(request);
    }

    private Mono<OptimizationResponse> processHistoryCompounding(String newDistantText, String existingSummary, int contextWindow, int dynamicThreshold) {
        String safeExisting = (existingSummary == null) ? "" : existingSummary;

        if (newDistantText == null || newDistantText.isBlank()) {
            return Mono.just(createBypassMetrics(safeExisting, tokenCounterService.countTokens(safeExisting)));
        }

        String compoundingHistoryPayload = safeExisting.isBlank() ? newDistantText
                : String.format("PREVIOUS RECAP:\n%s\n\nNEW CONVERSATION TRANSCRIPT:\n%s", safeExisting, newDistantText);

        int totalHistoryTokens = tokenCounterService.countTokens(compoundingHistoryPayload);

        if (totalHistoryTokens < dynamicThreshold) {
            return Mono.just(createBypassMetrics(compoundingHistoryPayload, totalHistoryTokens));
        }

        return processOptimization(compoundingHistoryPayload, totalHistoryTokens, dynamicThreshold, contextWindow, TargetType.HISTORY);
    }

    private String stitchGatewayPayload(String instruction, String compressedHistory, List<GatewayMessage> rawShortTerm) {
        StringBuilder payload = new StringBuilder();
        payload.append("CORE SYSTEM INSTRUCTION:\n")
                .append("Process the request inside the current active chat stream context.\n\n");

        if (compressedHistory != null && !compressedHistory.isBlank()) {
            payload.append("=== COMPRESSED HISTORICAL CONVERSATION MILESTONES ===\n")
                    .append(compressedHistory).append("\n\n");
        }

        if (!rawShortTerm.isEmpty()) {
            payload.append("=== IMMEDIATE VERBATIM CONVERSATION CONTEXT ===\n");
            for (GatewayMessage msg : rawShortTerm) {
                payload.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }
            payload.append("\n");
        }

        payload.append("=== TARGET USER INSTRUCTION TO EXECUTE ===\n").append(instruction);
        return payload.toString();
    }

    private OptimizationResponse mergeMetrics(
            OptimizationResponse inst, OptimizationResponse doc, int contextWindow,
            String finalPromptContent, int finalPromptTokens, int hypotheticalRawTokens,
            int turnTokensSaved, String actualProvider, String requestedProvider) {

        int fastTierInput = (inst.getTempFastTierInputTokens() != null ? inst.getTempFastTierInputTokens() : 0)
                + (doc.getTempFastTierInputTokens() != null ? doc.getTempFastTierInputTokens() : 0);

        int fastTierOutput = (inst.getTempFastTierOutputTokens() != null ? inst.getTempFastTierOutputTokens() : 0)
                + (doc.getTempFastTierOutputTokens() != null ? doc.getTempFastTierOutputTokens() : 0);

        double fastTierReduction = fastTierInput > 0 ? ((double) (fastTierInput - fastTierOutput) / fastTierInput) * 100 : 0.0;
        double totalSavingsPercent = hypotheticalRawTokens > 0 ? ((double) turnTokensSaved / hypotheticalRawTokens) * 100 : 0.0;

        String actionTaken = requestedProvider.equalsIgnoreCase(actualProvider)
                ? "EXECUTED_AS_REQUESTED"
                : "DOWNGRADED_TO_CHEAPER_MODEL";

        return OptimizationResponse.builder()
                .routingDecision(OptimizationResponse.RoutingDecision.builder()
                        .requestedProvider(requestedProvider)
                        .executedProvider(actualProvider)
                        .actionTaken(actionTaken)
                        .build())
                .billingImpact(OptimizationResponse.BillingImpact.builder()
                        .baselineTokens(hypotheticalRawTokens)
                        .billedTokens(finalPromptTokens)
                        .tokensSaved(turnTokensSaved)
                        .savingsPercentage(Double.parseDouble(String.format("%.2f", totalSavingsPercent)))
                        .build())
                .compressionInternals(OptimizationResponse.CompressionInternals.builder()
                        .tokensProcessed(fastTierInput)
                        .tokensOutput(fastTierOutput)
                        .compressionReduction(Double.parseDouble(String.format("%.2f", fastTierReduction)))
                        .compressionSummary(buildFinalPromptSummary(inst.getTempSummary(), doc.getTempSummary()))
                        .build())
                .payloadSnapshot(OptimizationResponse.PayloadSnapshot.builder()
                        .contextWindowSize(contextWindow)
                        .remainingHeadroom(Math.max(0, contextWindow - finalPromptTokens))
                        .finalPrompt(finalPromptContent)
                        .build())
                .build();
    }

    private OptimizationResponse createBypassMetrics(String text, int tokens) {
        OptimizationResponse response = new OptimizationResponse();
        response.setTempFastTierInputTokens(tokens);
        response.setTempFastTierOutputTokens(tokens);
        response.setTempSummary(text);
        return response;
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

    private String buildCombinedPrompt(String instruction, String documentContext) {
        if (documentContext == null || documentContext.isBlank()) {
            return instruction;
        }
        return String.format("%s\n\n--- Document Context ---\n%s", instruction, documentContext);
    }

    private String buildFinalPromptSummary(String optimizedInstruction, String optimizedHistory) {
        String safeInst = optimizedInstruction == null ? "" : optimizedInstruction;
        String safeHist = optimizedHistory == null ? "" : optimizedHistory;
        return String.format("Instruction Snapshot:\n%s\n\nHistory Snapshot:\n%s", safeInst, safeHist);
    }

    private String stitchNaivePayload(String instruction, List<GatewayMessage> allHistory) {
        StringBuilder payload = new StringBuilder();
        payload.append("CORE SYSTEM INSTRUCTION:\n")
                .append("Process the request inside the current active chat stream context.\n\n");

        if (!allHistory.isEmpty()) {
            payload.append("=== IMMEDIATE VERBATIM CONVERSATION CONTEXT ===\n");
            for (GatewayMessage msg : allHistory) {
                payload.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }
            payload.append("\n");
        }

        payload.append("=== TARGET USER INSTRUCTION TO EXECUTE ===\n").append(instruction);
        return payload.toString();
    }

    private record ContextResult(String text, String sourceType) {}
}