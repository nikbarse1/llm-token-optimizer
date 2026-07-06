package com.example.demo.advancePlusOne;

import com.example.demo.llmrouter.PrimaryLlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeminiLlmProviderAdapter implements LlmProvider {

    // Inject your existing functional Gemini service bean
    private final PrimaryLlmService primaryLlmService;

    @Override
    public Mono<String> askAi(String compiledPrompt) {
        log.info("Gateway routing execution to existing PrimaryLlmService...");

        // Directly invoke your existing working WebClient/Gemini execution block
        return primaryLlmService.askAi(compiledPrompt);
    }

    @Override
    public String getProviderName() {
        // This key maps exactly to what the registry registers and what the controller expects
        return "GEMINI";
    }
}
