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

                sessionState.setCompressedDistantHistory(optimizedHist.getSummary());

                String compiledPrompt = stitchGatewayPayload(
                        optimizedInst.getSummary(),
                        optimizedHist.getSummary(),
                        rawShortTermHistory
                );

                int finalPromptTokens = tokenCounterService.countTokens(compiledPrompt);

                String naivePayload = stitchNaivePayload(instruction, historicalChain);
                int hypotheticalRawTokens = tokenCounterService.countTokens(naivePayload);
                int turnTokensSaved = Math.max(0, hypotheticalRawTokens - finalPromptTokens);

                LlmProvider targetLlm = providerRegistry.getProvider(providerName);

                return targetLlm.askAi(compiledPrompt).flatMap(aiAnswer -> {

                    GatewayMessage userTurn = GatewayMessage.builder()
                            .role(GatewayMessage.Role.USER)
                            .content(instruction)
                            .tokenCount(instructionTokens)
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
                                hypotheticalRawTokens, turnTokensSaved
                        );

                        boolean wasOptimized = true;

                        return AiChatResponse.builder()
                                .userReadableMessage(aiAnswer)
                                .sourceType(contextResult.sourceType())
                                .wasOptimized(wasOptimized)
                                .optimizationMetrics((isDevMode && wasOptimized) ? mergedMetrics : null)
                                .chatId(chatId)
                                .build();
                    });
                });
            });
        });
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
        if (newDistantText.isBlank()) {
            return Mono.just(createBypassMetrics(existingSummary, tokenCounterService.countTokens(existingSummary)));
        }

        String compoundingHistoryPayload = existingSummary.isBlank() ? newDistantText
                : String.format("PREVIOUS RECAP:\n%s\n\nNEW CONVERSATION TRANSCRIPT:\n%s", existingSummary, newDistantText);

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

    private OptimizationResponse mergeMetrics(OptimizationResponse inst, OptimizationResponse doc, int contextWindow, String finalPromptContent,
                                              int finalPromptTokens, int hypotheticalRawTokens,
                                              int turnTokensSaved) {

        // 1. Internal Groq Math (Strictly using the new clearer fields)
        int groqInput = (inst.getGroqInputTokens() != null ? inst.getGroqInputTokens() : 0)
                + (doc.getGroqInputTokens() != null ? doc.getGroqInputTokens() : 0);

        int groqOutput = (inst.getGroqOutputTokens() != null ? inst.getGroqOutputTokens() : 0)
                + (doc.getGroqOutputTokens() != null ? doc.getGroqOutputTokens() : 0);

        double groqReduction = groqInput > 0 ? ((double) (groqInput - groqOutput) / groqInput) * 100 : 0.0;

        // 2. Total Savings Math (The proof of value)
        double totalSavingsPercent = hypotheticalRawTokens > 0
                ? ((double) turnTokensSaved / hypotheticalRawTokens) * 100
                : 0.0;

        return OptimizationResponse.builder()
                // The Savings Dashboard
                .hypotheticalRawTokens(hypotheticalRawTokens)
                .finalPromptTokens(finalPromptTokens)
                .turnTokensSaved(turnTokensSaved)
                .totalSavingsPercentage(Double.parseDouble(String.format("%.2f", totalSavingsPercent)))

                // The Internal Groq Metrics
                .groqInputTokens(groqInput)
                .groqOutputTokens(groqOutput)
                .groqReductionPercentage(Double.parseDouble(String.format("%.2f", groqReduction)))
                .summary(buildFinalPromptSummary(inst.getSummary(), doc.getSummary()))

                // Context / Payload Tracking
                .contextWindow(contextWindow)
                .headroomBefore(Math.max(0, contextWindow - hypotheticalRawTokens))
                .headroomAfter(Math.max(0, contextWindow - finalPromptTokens))
                .finalPromptContent(finalPromptContent)
                .build();
    }

    private OptimizationResponse createBypassMetrics(String text, int tokens) {
        return OptimizationResponse.builder()
                .groqInputTokens(tokens)         // CORRECTED
                .groqOutputTokens(tokens)        // CORRECTED
                .groqReductionPercentage(0.0)    // CORRECTED
                .summary(text)
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

    private String buildCombinedPrompt(String instruction, String documentContext) {
        if (documentContext == null || documentContext.isBlank()) {
            return instruction;
        }
        return String.format("%s\n\n--- Document Context ---\n%s", instruction, documentContext);
    }

    private String buildFinalPromptSummary(String optimizedInstruction, String optimizedHistory) {
        return String.format("Instruction Snapshot:\n%s\n\nHistory Snapshot:\n%s", optimizedInstruction, optimizedHistory);
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