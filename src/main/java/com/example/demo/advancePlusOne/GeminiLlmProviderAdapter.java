package com.example.demo.advancePlusOne;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GeminiLlmProviderAdapter implements LlmProvider {

    private final ChatClient chatClient;

    public GeminiLlmProviderAdapter(
            @Qualifier("googleGenAiChatModel") ChatModel chatModel) {

        this.chatClient = ChatClient.create(chatModel);
    }

    @Override
    public Mono<ChatResponse> askAi(Prompt prompt) {
        log.info("Gateway routing execution to Spring AI Gemini...");
        return Mono.fromCallable(() -> chatClient.prompt(prompt).call().chatResponse())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getProviderName() {
        return "GEMINI";
    }
}