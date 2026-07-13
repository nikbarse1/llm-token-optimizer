package com.example.demo.advancePlusOne;

import com.example.demo.llmrouter.AiChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/chat")
@RequiredArgsConstructor
@Slf4j
public class AiChatController2 {

    private final AdvancedGatewayOrchestrationService gatewayOrchestrationService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<AiChatResponse> chat(
            @RequestParam("instruction") String instruction,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "chatId", required = false) String chatId,
            @RequestParam(value = "provider", defaultValue = "GEMINI") String provider,
            @RequestParam(value = "contextWindow", defaultValue = "8192") int contextWindow,
            @RequestHeader(value = "X-Developer-Mode", defaultValue = "false") boolean isDevMode
    ) {
        // Generate a new unique session identifier if the client did not provide one
        String activeChatId = (chatId == null || chatId.isBlank()) ? UUID.randomUUID().toString() : chatId;

        log.info("Incoming gateway request - ChatId: {}, Provider: {}, File Present: {}, URL Present: {}, DevMode: {}",
                activeChatId, provider, (file != null && !file.isEmpty()), (url != null && !url.isBlank()), isDevMode);

        return gatewayOrchestrationService.processStatefulChat(
                instruction,
                file,
                url,
                activeChatId,
                provider,
                contextWindow,
                isDevMode
        ).map(response -> {
            // Explicitly inject the active chatId back into the response payload
            // so the frontend knows what ID to pass back on subsequent turns
            response.setChatId(activeChatId);
            return response;
        });
    }
}
