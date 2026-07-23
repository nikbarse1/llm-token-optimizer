package com.example.demo.advancePlusOne;

import com.example.demo.*;
import com.example.demo.OptimizationRequest.TargetType;
import com.example.demo.embeddings.GeminiEmbeddingService;
import com.example.demo.embeddings.SemanticCacheRepository;
import com.example.demo.llmrouter.AiChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    // Spring AI Native Components
    private final ChatMemory chatMemory;

    // Semantic Caching services
    private final GeminiEmbeddingService embeddingService;
    private final SemanticCacheRepository cacheRepository;

    private static final int SHORT_TERM_WINDOW_TURNS = 3; // 3 Pairs (6 messages)
    private static final int INSTRUCTION_THRESHOLD_TOKENS = 100;
    private static final int HISTORY_COMPRESSION_THRESHOLD = 200;

    public Mono<AiChatResponse> processStatefulChat(
            String instruction, MultipartFile file, String url, String chatId,
            String providerName, int contextWindow, boolean isDevMode) {

        return embeddingService.generateEmbedding(instruction)
                .flatMap(embedding -> Mono.fromCallable(() -> cacheRepository.findCachedResponse(embedding))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(cachedAnswer -> {
                            if (cachedAnswer != null) {
                                log.info("⚡ Returning instant response from Semantic Cache for chatId: {}", chatId);
                                return handleCacheHit(instruction, cachedAnswer, chatId, isDevMode);
                            }
                            return executeFullLlmPipeline(
                                    instruction, file, url, chatId, providerName, contextWindow, isDevMode, embedding
                            );
                        })
                )
                .switchIfEmpty(executeFullLlmPipeline(
                        instruction, file, url, chatId, providerName, contextWindow, isDevMode, null
                ));
    }

    private Mono<AiChatResponse> executeFullLlmPipeline(
            String instruction, MultipartFile file, String url, String chatId,
            String providerName, int contextWindow, boolean isDevMode, List<Double> instructionEmbedding) {

        Mono<ContextResult> contextResultMono = resolveDocumentContext(file, url);

        // Retrieve memory natively via Spring AI
        List<Message> sessionHistory = chatMemory.get(chatId);

        return contextResultMono.flatMap(contextResult -> {
            String augmentedInstruction = buildCombinedPrompt(instruction, contextResult.text());
            int instructionTokens = tokenCounterService.countTokens(augmentedInstruction);

            // Separate out the compressed distant history system message if it exists
            String existingCompressedHistory = "";
            List<Message> rawHistoryTurns = new ArrayList<>();
            for (Message msg : sessionHistory) {
                if (msg instanceof SystemMessage sys && sys.getText().startsWith("PREVIOUS RECAP:")) {
                    existingCompressedHistory = sys.getText().replace("PREVIOUS RECAP:\n", "");
                } else {
                    rawHistoryTurns.add(msg);
                }
            }

            int splitIndex = Math.max(0, rawHistoryTurns.size() - (SHORT_TERM_WINDOW_TURNS * 2));
            List<Message> distantHistory = rawHistoryTurns.subList(0, splitIndex);
            List<Message> rawShortTermHistory = rawHistoryTurns.subList(splitIndex, rawHistoryTurns.size());

            String newDistantTurnsBlock = distantHistory.stream()
                    .map(msg -> msg.getMessageType().getValue().toUpperCase() + ": " + msg.getText())
                    .collect(Collectors.joining("\n"));

            Mono<OptimizationResponse> instructionTrack = processOptimization(
                    augmentedInstruction, instructionTokens, INSTRUCTION_THRESHOLD_TOKENS, contextWindow, TargetType.INSTRUCTION
            );

            int estimatedHistoryTokens = tokenCounterService.countTokens(newDistantTurnsBlock)
                    + tokenCounterService.countTokens(existingCompressedHistory);
            int dynamicHistoryThreshold = ((instructionTokens + estimatedHistoryTokens) > (contextWindow * 0.8))
                    ? HISTORY_COMPRESSION_THRESHOLD : Integer.MAX_VALUE;

            Mono<OptimizationResponse> historyTrack = processHistoryCompounding(
                    newDistantTurnsBlock, existingCompressedHistory, contextWindow, dynamicHistoryThreshold
            );

            return Mono.zip(instructionTrack, historyTrack).flatMap(optTuple -> {
                OptimizationResponse optimizedInst = optTuple.getT1();
                OptimizationResponse optimizedHist = optTuple.getT2();

                // Create the Structured Prompt using Spring AI Standards
                List<Message> messagesToSend = new ArrayList<>();

                String systemRules = "Process the request inside the current active chat stream context.";
                if (optimizedHist.getTempSummary() != null && !optimizedHist.getTempSummary().isBlank()) {
                    systemRules += "\n\nPREVIOUS RECAP:\n" + optimizedHist.getTempSummary();
                }
                messagesToSend.add(new SystemMessage(systemRules));
                messagesToSend.addAll(rawShortTermHistory);
                messagesToSend.add(new UserMessage(optimizedInst.getTempSummary()));

                Prompt compiledPrompt = new Prompt(messagesToSend);

                // Pre-count logic for routing
                int heuristicPromptTokens = tokenCounterService.countTokens(compiledPrompt.getInstructions().toString());
                int naiveHypotheticalTokens = tokenCounterService.countTokens(instruction + "\n" + rawHistoryTurns.toString());

                ProviderRoutingContext routingContext = ProviderRoutingContext.builder()
                        .requestedProvider(providerName)
                        .instructionTokens(instructionTokens)
                        .finalPromptTokens(heuristicPromptTokens)
                        .hasHeavyContext(!contextResult.text().isBlank())
                        .build();

                String smartProviderName = llmRouterService.route(routingContext);
                LlmProvider targetLlm = providerRegistry.getProvider(smartProviderName);

                return targetLlm.askAi(compiledPrompt).flatMap(chatResponse -> {
                    String aiAnswerText = chatResponse.getResult().getOutput().getText();
                    org.springframework.ai.chat.metadata.Usage actualUsage = chatResponse.getMetadata().getUsage();

                    if (instructionEmbedding != null && !instructionEmbedding.isEmpty()) {
                        Mono.fromRunnable(() -> cacheRepository.cacheResponse(instruction, aiAnswerText, instructionEmbedding))
                                .subscribeOn(Schedulers.boundedElastic()).subscribe();
                    }

                    // Update Spring AI ChatMemory securely
                    List<Message> newMemoryState = new ArrayList<>();
                    newMemoryState.add(new SystemMessage("PREVIOUS RECAP:\n" + optimizedHist.getTempSummary()));
                    newMemoryState.addAll(rawShortTermHistory);
                    newMemoryState.add(new UserMessage(optimizedInst.getTempSummary()));
                    newMemoryState.add(new AssistantMessage(aiAnswerText));

                    chatMemory.clear(chatId);
                    chatMemory.add(chatId, newMemoryState);

                    OptimizationResponse mergedMetrics = mergeMetrics(
                            optimizedInst, optimizedHist, contextWindow,
                            compiledPrompt.getInstructions().toString(), actualUsage,
                            naiveHypotheticalTokens, smartProviderName, providerName
                    );

                    return Mono.just(AiChatResponse.builder()
                            .userReadableMessage(aiAnswerText)
                            .sourceType(contextResult.sourceType())
                            .wasOptimized(true)
                            .optimizationMetrics(isDevMode ? mergedMetrics : null)
                            .chatId(chatId)
                            .build());
                });
            });
        });
    }

    private Mono<AiChatResponse> handleCacheHit(String instruction, String cachedAnswer, String chatId, boolean isDevMode) {
        List<Message> newMessages = List.of(new UserMessage(instruction), new AssistantMessage(cachedAnswer));
        chatMemory.add(chatId, newMessages);

        return Mono.just(AiChatResponse.builder()
                .userReadableMessage(cachedAnswer)
                .sourceType("SEMANTIC_CACHE_HIT")
                .wasOptimized(true)
                .chatId(chatId)
                .build());
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
                .usageMetrics(OptimizationResponse.UsageMetrics.builder() // The new DTO structure
                        .expectedTokensBeforeOptimization(hypotheticalRawTokens)
                        .actualPromptTokens(promptTokens)
                        .actualCompletionTokens(completionTokens)
                        .actualTotalTokens(totalTokens)
                        .tokensSaved(tokensSaved)
                        .savingsPercentage(Double.parseDouble(String.format("%.2f", savingsPercent)))
                        .build())
                .payloadSnapshot(OptimizationResponse.PayloadSnapshot.builder()
                        .contextWindowSize(contextWindow)
                        .remainingHeadroom(Math.max(0, contextWindow - (int)totalTokens))
                        .finalPrompt(finalPromptContent)
                        .build())
                .build();
    }

    private Mono<OptimizationResponse> processOptimization(String text, int tokens, int threshold, int contextWindow, TargetType type) {
        if (text == null || text.isBlank()) return Mono.just(createBypassMetrics("", 0));
        if (tokens < threshold) return Mono.just(createBypassMetrics(text, tokens));

        OptimizationRequest request = new OptimizationRequest();
        request.setDocument(text);
        request.setTargetType(type);
        return tokenOptimizationService.optimizeDocument(request);
    }

    private Mono<OptimizationResponse> processHistoryCompounding(String newDistantText, String existingSummary, int contextWindow, int dynamicThreshold) {
        String safeExisting = (existingSummary == null) ? "" : existingSummary;
        if (newDistantText == null || newDistantText.isBlank()) {
            return Mono.just(createBypassMetrics(safeExisting, tokenCounterService.countTokens(safeExisting)));
        }

        String compoundingPayload = safeExisting.isBlank() ? newDistantText
                : String.format("PREVIOUS RECAP:\n%s\n\nNEW CONVERSATION TRANSCRIPT:\n%s", safeExisting, newDistantText);

        int tokens = tokenCounterService.countTokens(compoundingPayload);
        if (tokens < dynamicThreshold) return Mono.just(createBypassMetrics(compoundingPayload, tokens));

        return processOptimization(compoundingPayload, tokens, dynamicThreshold, contextWindow, TargetType.HISTORY);
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
        if (documentContext == null || documentContext.isBlank()) return instruction;
        return String.format("%s\n\n--- Document Context ---\n%s", instruction, documentContext);
    }

    private record ContextResult(String text, String sourceType) {}
}