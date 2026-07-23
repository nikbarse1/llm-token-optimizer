package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class LLMSummarizationService {

    private static final int CHUNK_CHAR_SIZE = 12_000;
    private static final int CHUNK_CONCURRENCY = 1;

    private final ChatClient chatClient;

    public LLMSummarizationService(
            @Qualifier("openAiChatModel") ChatModel fastTierChatModel) {
        this.chatClient = ChatClient.create(fastTierChatModel);
        log.info("LLMSummarizationService configured using Spring AI Fast-Tier Engine.");
    }

    public Mono<String> smartCompress(String text, OptimizationRequest.TargetType targetType) {
        if (text == null || text.isBlank()) {
            return Mono.just("Empty input.");
        }

        List<String> chunks = splitIntoChunks(text, CHUNK_CHAR_SIZE);

        if (chunks.size() == 1) {
            return callFastTier(buildSmartUserPrompt(chunks.get(0), targetType), chunks.get(0));
        }

        log.info("Input is large ({} chars) - splitting into {} chunks. Processing sequentially.", text.length(), chunks.size());

        return Flux.fromIterable(chunks)
                .index()
                .flatMapSequential(indexed -> callFastTier(
                        buildMapUserPrompt(indexed.getT2(), indexed.getT1().intValue() + 1, chunks.size()),
                        indexed.getT2()), CHUNK_CONCURRENCY)
                .collectList()
                .flatMap(extractedNotes -> {
                    String combinedNotes = String.join("\n\n", extractedNotes);
                    return callFastTier(buildSmartUserPrompt(combinedNotes, targetType), combinedNotes);
                });
    }

    private String getBaseRules() {
        return """
               CRITICAL RULES:
               1. DO NOT summarize, alter, or truncate any source code, JSON, XML, scripts, or configuration files. Preserve all syntax and code blocks verbatim.
               2. Remove filler words, conversational pleasantries, and redundant prose.
               3. Optimize for token efficiency while retaining 100% of the technical accuracy and factual data.
               """;
    }

    private String buildSmartUserPrompt(String text, OptimizationRequest.TargetType targetType) {
        if (targetType == OptimizationRequest.TargetType.INSTRUCTION) {
            return "Refine and condense the following instruction. Preserve all requirements and constraints. Be concise.\n\nInput:\n" + text;
        } else if (targetType == OptimizationRequest.TargetType.HISTORY) {
            return """
                   Extract and append immutable facts, key technical decisions, variables, and architectural constraints from this conversation transcript. 
                   Do NOT write prose or paragraphs. Output STRICTLY as a highly condensed, bulleted key-value ledger.
                   
                   Transcript to Process:
                   """ + text;
        } else {
            return "Compress the following document. Retain all factual data and parameters. Output strictly as bulleted notes.\n\nDocument:\n" + text;
        }
    }

    private String buildMapUserPrompt(String chunk, int partIndex, int totalParts) {
        return "You are helping compress a large input (Part " + partIndex + " of " + totalParts + ").\n\n"
                + "Extract every important fact, decision, instruction, and ALL code verbatim as concise notes. Output ONLY the extracted notes:\n\n" + chunk;
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] logicalParagraphs = text.split("(?=\\n\\n|```|\\{)");
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : logicalParagraphs) {
            if (currentChunk.length() + paragraph.length() > chunkSize) {
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                if (paragraph.length() > chunkSize) {
                    chunks.addAll(fallbackHardSplit(paragraph, chunkSize));
                    continue;
                }
            }
            currentChunk.append(paragraph);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }

    private List<String> fallbackHardSplit(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + chunkSize, length);
            chunks.add(text.substring(start, end).trim());
            start = end;
        }
        return chunks;
    }

    private Mono<String> callFastTier(String userPrompt, String fallbackSource) {
        return Mono.fromCallable(() -> chatClient.prompt()
                        .system(getBaseRules()) // Injected natively as a system message
                        .user(userPrompt)
                        .call()
                        .content())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Error calling Fast-Tier API: {}, using fallback", e.getMessage());
                    return Mono.just(fallbackSource);
                });
    }
}