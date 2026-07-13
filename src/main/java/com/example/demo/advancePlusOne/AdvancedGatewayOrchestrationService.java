package com.example.demo.advancePlusOne;

import com.example.demo.*;
import com.example.demo.OptimizationRequest.TargetType;
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

    private static final int SHORT_TERM_WINDOW_TURNS = 3;
    private static final int INSTRUCTION_THRESHOLD_TOKENS = 100;
    private static final int HISTORY_COMPRESSION_THRESHOLD = 200;

    private static final int ROUTER_SIMPLE_INSTRUCTION_LIMIT = 50;
    private static final int ROUTER_SIMPLE_PROMPT_LIMIT = 400;
    private static final String FALLBACK_CHEAP_PROVIDER = "FAST_TIER";

    public Mono<AiChatResponse> processStatefulChat(
            String instruction,
            MultipartFile file,
            String url,
            String chatId,
            String providerName,
            int contextWindow,
            boolean isDevMode
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

            Mono<OptimizationResponse> historyTrack = processHistoryCompounding(
                    newDistantTurnsBlock, sessionState.getCompressedDistantHistory(), contextWindow
            );

            return Mono.zip(instructionTrack, historyTrack).flatMap(optTuple -> {
                OptimizationResponse optimizedInst = optTuple.getT1();
                OptimizationResponse optimizedHist = optTuple.getT2();

                // Save the new compressed history back to the session state
                sessionState.setCompressedDistantHistory(optimizedHist.getTempSummary());

                String compiledPrompt = stitchGatewayPayload(
                        optimizedInst.getTempSummary(),
                        optimizedHist.getTempSummary(),
                        rawShortTermHistory
                );

                int finalPromptTokens = tokenCounterService.countTokens(compiledPrompt);

                String naivePayload = stitchNaivePayload(instruction, historicalChain);
                int hypotheticalRawTokens = tokenCounterService.countTokens(naivePayload);

                int turnTokensSaved = Math.max(0, hypotheticalRawTokens - finalPromptTokens);

                String smartProviderName = determineOptimalProvider(
                        providerName,
                        instructionTokens,
                        finalPromptTokens,
                        contextResult
                );

                LlmProvider targetLlm = providerRegistry.getProvider(smartProviderName);

                return targetLlm.askAi(compiledPrompt).flatMap(aiAnswer -> {

                    String contentToSave = (contextResult.text() != null && !contextResult.text().isBlank())
                            ? optimizedInst.getTempSummary()
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
                                compiledPrompt, finalPromptTokens,
                                hypotheticalRawTokens, turnTokensSaved,
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

    private String determineOptimalProvider(String requestedProvider, int instructionTokens, int finalPromptTokens, ContextResult contextResult) {
        boolean hasHeavyContext = contextResult.text() != null && !contextResult.text().isBlank();

        if (!hasHeavyContext && instructionTokens < 25 && finalPromptTokens < ROUTER_SIMPLE_PROMPT_LIMIT) {
            log.info("Cascading Router Triggered: Trivial request detected. Downgrading to {}.", FALLBACK_CHEAP_PROVIDER);
            return FALLBACK_CHEAP_PROVIDER;
        }

        return requestedProvider;
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

    private Mono<OptimizationResponse> processHistoryCompounding(String newDistantText, String existingSummary, int contextWindow) {
        // Defensive check: Added null safety for existingSummary
        String safeExisting = (existingSummary == null) ? "" : existingSummary;

        if (newDistantText == null || newDistantText.isBlank()) {
            return Mono.just(createBypassMetrics(safeExisting, tokenCounterService.countTokens(safeExisting)));
        }

        String compoundingHistoryPayload = safeExisting.isBlank() ? newDistantText
                : String.format("PREVIOUS RECAP:\n%s\n\nNEW CONVERSATION TRANSCRIPT:\n%s", safeExisting, newDistantText);

        int totalHistoryTokens = tokenCounterService.countTokens(compoundingHistoryPayload);

        if (totalHistoryTokens < HISTORY_COMPRESSION_THRESHOLD) {
            return Mono.just(createBypassMetrics(compoundingHistoryPayload, totalHistoryTokens));
        }

        return processOptimization(compoundingHistoryPayload, totalHistoryTokens, HISTORY_COMPRESSION_THRESHOLD, contextWindow, TargetType.DOCUMENT);
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