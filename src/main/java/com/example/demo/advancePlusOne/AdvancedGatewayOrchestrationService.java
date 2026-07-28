package com.example.demo.advancePlusOne;

import com.example.demo.*;
import com.example.demo.OptimizationRequest.TargetType;
import com.example.demo.embeddings.GeminiEmbeddingService;
import com.example.demo.embeddings.SemanticCacheRepository;
import com.example.demo.embeddings.VectorizedHistoryService;
import com.example.demo.llmrouter.AiChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdvancedGatewayOrchestrationService {

    private final LlmProviderRegistry providerRegistry;
    private final TokenOptimizationService tokenOptimizationService;
    private final TokenCounterService tokenCounterService;
    private final FileParserService fileParserService;
    private final WebScraperService webScraperService;
    private final LlmRouterService llmRouterService;

    // Phase 2 & 3 Services
    private final DocumentRagService documentRagService;
    private final VectorizedHistoryService vectorizedHistoryService;

    // Spring AI Native Components
    private final ChatMemory chatMemory;

    // Semantic Caching services
    private final GeminiEmbeddingService embeddingService;
    private final SemanticCacheRepository cacheRepository;

    public Mono<AiChatResponse> processStatefulChat(
            String instruction, MultipartFile file, String url, String chatId,
            String providerName, int contextWindow, boolean isDevMode) {

        // Step 1: Resolve attached document or URL context first
        return resolveDocumentContext(file, url).flatMap(contextResult -> {
            String rawContext = contextResult.text();
            log.info("Resolved document context - sourceType: {}, rawContextLength: {}",
                    contextResult.sourceType(), rawContext.length());

            // Step 2: Context-Aware Semantic Cache Lookup (Instruction + Context Hash)
            return embeddingService.generateEmbedding(instruction)
                    .flatMap(embedding -> Mono.fromCallable(() -> cacheRepository.findCachedResponse(embedding, rawContext))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(cachedAnswer -> {
                                if (cachedAnswer != null) {
                                    log.info("⚡ Returning instant response from Context-Aware Semantic Cache for chatId: {}", chatId);
                                    return handleCacheHit(instruction, cachedAnswer, chatId, isDevMode);
                                }
                                return executeFullLlmPipeline(
                                        instruction, rawContext, contextResult.sourceType(), chatId,
                                        providerName, contextWindow, isDevMode, embedding
                                );
                            })
                    )
                    .switchIfEmpty(executeFullLlmPipeline(
                            instruction, rawContext, contextResult.sourceType(), chatId,
                            providerName, contextWindow, isDevMode, null
                    ));
        });
    }

    private Mono<AiChatResponse> executeFullLlmPipeline(
            String instruction, String rawContext, String sourceType, String chatId,
            String providerName, int contextWindow, boolean isDevMode, List<Double> instructionEmbedding) {

        // Step 3: Index raw document into Redis RAG store if context exists
        Mono<Void> ragIndexMono = (!rawContext.isBlank())
                ? documentRagService.indexDocument(chatId, rawContext)
                : Mono.empty();

        return ragIndexMono.then(Mono.defer(() -> {

            // Step 4: Retrieve top relevant context chunks via RAG
            Mono<String> retrievedContextMono = (!rawContext.isBlank())
                    ? documentRagService.retrieveRelevantContext(chatId, instruction, 3)
                    : Mono.just("");

            // Step 5: Retrieve semantically relevant historical turns
            Mono<String> relevantHistoryMono = vectorizedHistoryService.retrieveRelevantHistory(chatId, instruction, 5);

            return Mono.zip(retrievedContextMono, relevantHistoryMono).flatMap(tuple -> {
                String ragContextSnippet = tuple.getT1();
                String semanticHistorySnippet = tuple.getT2();

                // Build augmented instruction with retrieved RAG context
                String augmentedInstruction = buildAugmentedInstruction(instruction, ragContextSnippet);
                log.info("Augmented instruction - chatId: {}, length: {}, preview: '{}'",
                        chatId, augmentedInstruction.length(), truncate(augmentedInstruction, 300));

                int instructionTokens = tokenCounterService.countTokens(augmentedInstruction);
                int historyTokens = tokenCounterService.countTokens(semanticHistorySnippet);

                int estimatedTotalTokens = instructionTokens + historyTokens;

                // Step 6: Dynamic Headroom Threshold Evaluation (75% of model's context window)
                double maxAllowedTokens = contextWindow * 0.75;
                boolean needsOptimization = estimatedTotalTokens > maxAllowedTokens;

                log.info("Token Estimate: {} / Context Window Threshold: {} (Needs Optimization: {})",
                        estimatedTotalTokens, maxAllowedTokens, needsOptimization);

                Mono<OptimizationResponse> instructionTrack = needsOptimization
                        ? processOptimization(augmentedInstruction, instructionTokens, contextWindow, TargetType.INSTRUCTION)
                        : Mono.just(createBypassMetrics(augmentedInstruction, instructionTokens));

                Mono<OptimizationResponse> historyTrack = needsOptimization
                        ? processOptimization(semanticHistorySnippet, historyTokens, contextWindow, TargetType.HISTORY)
                        : Mono.just(createBypassMetrics(semanticHistorySnippet, historyTokens));

                return Mono.zip(instructionTrack, historyTrack).flatMap(optTuple -> {
                    OptimizationResponse optimizedInst = optTuple.getT1();
                    OptimizationResponse optimizedHist = optTuple.getT2();

                    // Step 7: Construct Structured Spring AI Prompt
                    List<Message> messagesToSend = new ArrayList<>();

                    String systemRules = "You are a helpful, highly accurate AI assistant. Process the request inside the current active chat stream context.";
                    if (optimizedHist.getTempSummary() != null && !optimizedHist.getTempSummary().isBlank()) {
                        systemRules += "\n\n=== RELEVANT CONVERSATION HISTORY ===\n" + optimizedHist.getTempSummary();
                    }
                    messagesToSend.add(new SystemMessage(systemRules));

                    // Add short-term memory turns from Spring AI ChatMemory
                    List<Message> shortTermMemory = chatMemory.get(chatId);
                    if (shortTermMemory != null && !shortTermMemory.isEmpty()) {
                        messagesToSend.addAll(shortTermMemory);
                    }

                    messagesToSend.add(new UserMessage(optimizedInst.getTempSummary()));

                    Prompt compiledPrompt = new Prompt(messagesToSend);
                    log.info("Compiled prompt - chatId: {}, messages: {}, promptPreview: '{}'",
                            chatId, compiledPrompt.getInstructions().size(), truncate(compiledPrompt.getInstructions().toString(), 500));

                    int heuristicPromptTokens = tokenCounterService.countTokens(compiledPrompt.getInstructions().toString());
                    int hypotheticalRawTokens = tokenCounterService.countTokens(instruction + "\n" + rawContext + "\n" + semanticHistorySnippet);

                    ProviderRoutingContext routingContext = ProviderRoutingContext.builder()
                            .requestedProvider(providerName)
                            .instructionTokens(instructionTokens)
                            .finalPromptTokens(heuristicPromptTokens)
                            .hasHeavyContext(!rawContext.isBlank())
                            .build();

                    String smartProviderName = llmRouterService.route(routingContext);
                    final String[] executedProvider = { smartProviderName };
                    LlmProvider targetLlm = providerRegistry.getProvider(smartProviderName);

                    // Step 8: Execute LLM Completion (fallback to GEMINI if the primary provider fails)
                    return targetLlm.askAi(compiledPrompt)
                            .doOnError(e -> log.warn("Provider {} failed: {}", smartProviderName, e.getMessage()))
                            .onErrorResume(e -> {
                                if (!"GEMINI".equalsIgnoreCase(smartProviderName)) {
                                    log.warn("Falling back from {} to GEMINI due to error: {}", smartProviderName, e.getMessage());
                                    executedProvider[0] = "GEMINI";
                                    LlmProvider fallbackLlm = providerRegistry.getProvider("GEMINI");
                                    return fallbackLlm.askAi(compiledPrompt)
                                            .doOnError(fallbackError -> log.error("GEMINI fallback also failed: {}", fallbackError.getMessage()));
                                }
                                return Mono.error(e);
                            })
                            .flatMap(chatResponse -> {
                                log.info("Received chat response - provider: {}, chatId: {}", executedProvider[0], chatId);
                                String aiAnswerText = chatResponse.getResult().getOutput().getText();
                                org.springframework.ai.chat.metadata.Usage actualUsage = chatResponse.getMetadata().getUsage();
                                log.info("AI answer - chatId: {}, answerLength: {}, usage: {}",
                                        chatId, aiAnswerText != null ? aiAnswerText.length() : 0, actualUsage);

                                // Cache hit saving in background (Context-Aware)
                                if (instructionEmbedding != null && !instructionEmbedding.isEmpty()) {
                                    Mono.fromRunnable(() -> cacheRepository.cacheResponse(instruction, aiAnswerText, instructionEmbedding, rawContext))
                                            .subscribeOn(Schedulers.boundedElastic()).subscribe();
                                }

                                // Step 9: Save turns to both Spring AI ChatMemory and Vectorized History
                                chatMemory.add(chatId, List.of(new UserMessage(instruction), new AssistantMessage(aiAnswerText)));

                                Mono<Void> saveUserHistory = vectorizedHistoryService.saveMessageToHistory(chatId, "USER", instruction);
                                Mono<Void> saveAssistantHistory = vectorizedHistoryService.saveMessageToHistory(chatId, "ASSISTANT", aiAnswerText);

                                return Mono.when(saveUserHistory, saveAssistantHistory).then(Mono.defer(() -> {

                                    OptimizationResponse mergedMetrics = mergeMetrics(
                                            optimizedInst, optimizedHist, contextWindow,
                                            compiledPrompt.getInstructions().toString(), actualUsage,
                                            hypotheticalRawTokens, executedProvider[0], providerName
                                    );

                                    AiChatResponse response = AiChatResponse.builder()
                                            .userReadableMessage(aiAnswerText)
                                            .sourceType(sourceType)
                                            .wasOptimized(needsOptimization)
                                            .optimizationMetrics(isDevMode ? mergedMetrics : null)
                                            .chatId(chatId)
                                            .build();

                                    log.info("Returning AiChatResponse - chatId: {}, sourceType: {}, wasOptimized: {}, responseLength: {}",
                                            chatId, response.getSourceType(), response.isWasOptimized(),
                                            response.getUserReadableMessage() != null ? response.getUserReadableMessage().length() : 0);
                                    return Mono.just(response);
                                }));
                            });
                });
            });
        }));
    }

    private Mono<AiChatResponse> handleCacheHit(String instruction, String cachedAnswer, String chatId, boolean isDevMode) {
        chatMemory.add(chatId, List.of(new UserMessage(instruction), new AssistantMessage(cachedAnswer)));
        log.info("Returning cached AiChatResponse - chatId: {}, responseLength: {}",
                chatId, cachedAnswer != null ? cachedAnswer.length() : 0);

        return vectorizedHistoryService.saveMessageToHistory(chatId, "USER", instruction)
                .then(vectorizedHistoryService.saveMessageToHistory(chatId, "ASSISTANT", cachedAnswer))
                .then(Mono.just(AiChatResponse.builder()
                        .userReadableMessage(cachedAnswer)
                        .sourceType("SEMANTIC_CACHE_HIT")
                        .wasOptimized(true)
                        .chatId(chatId)
                        .build()));
    }

    private OptimizationResponse mergeMetrics(
            OptimizationResponse inst, OptimizationResponse doc, int contextWindow,
            String finalPromptContent, org.springframework.ai.chat.metadata.Usage actualUsage,
            int hypotheticalRawTokens, String actualProvider, String requestedProvider) {

        long promptTokens = actualUsage != null ? actualUsage.getPromptTokens() : 0L;
        long completionTokens = actualUsage != null ? actualUsage.getCompletionTokens() : 0L;
        long totalTokens = actualUsage != null ? actualUsage.getTotalTokens() : 0L;

        long tokensSaved = Math.max(0, hypotheticalRawTokens - totalTokens);
        double savingsPercent = hypotheticalRawTokens > 0 ? ((double) tokensSaved / hypotheticalRawTokens) * 100 : 0.0;

        return OptimizationResponse.builder()
                .routingDecision(OptimizationResponse.RoutingDecision.builder()
                        .requestedProvider(requestedProvider)
                        .executedProvider(actualProvider)
                        .build())
                .usageMetrics(OptimizationResponse.UsageMetrics.builder()
                        .expectedTokensBeforeOptimization(hypotheticalRawTokens)
                        .actualPromptTokens(promptTokens)
                        .actualCompletionTokens(completionTokens)
                        .actualTotalTokens(totalTokens)
                        .tokensSaved(tokensSaved)
                        .savingsPercentage(Double.parseDouble(String.format("%.2f", savingsPercent)))
                        .build())
                .payloadSnapshot(OptimizationResponse.PayloadSnapshot.builder()
                        .contextWindowSize(contextWindow)
                        .remainingHeadroom(Math.max(0, contextWindow - (int) totalTokens))
                        .finalPrompt(finalPromptContent)
                        .build())
                .build();
    }

    private Mono<OptimizationResponse> processOptimization(String text, int tokens, int contextWindow, TargetType type) {
        if (text == null || text.isBlank()) return Mono.just(createBypassMetrics("", 0));

        OptimizationRequest request = new OptimizationRequest();
        request.setDocument(text);
        request.setContextWindow(contextWindow);
        request.setTargetType(type);
        return tokenOptimizationService.optimizeDocument(request);
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

    private String buildAugmentedInstruction(String instruction, String ragSnippet) {
        if (ragSnippet == null || ragSnippet.isBlank()) return instruction;
        return String.format("%s\n\n--- Relevant Document Context ---\n%s", instruction, ragSnippet);
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    private record ContextResult(String text, String sourceType) {}
}