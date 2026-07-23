package com.example.demo.advancePlusOne;

import com.example.demo.llmrouter.AiChatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "AI Chat Gateway", description = "Stateful, token-optimized conversational AI routing engine")
public class AiChatController2 {

    private final AdvancedGatewayOrchestrationService gatewayOrchestrationService;

    @Operation(
            summary = "Submit a prompt to the optimized AI gateway",
            description = "Processes a user instruction alongside optional files or URLs, optimizing the context window and intelligently routing the request to the best LLM provider.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successful AI execution",
                            content = @Content(schema = @Schema(implementation = AiChatResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                    @ApiResponse(responseCode = "500", description = "Internal processing or LLM provider error")
            }
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AiChatResponse> chat(
            @Parameter(description = "The core instruction or question for the AI")
            @RequestParam("instruction") String instruction,

            @Parameter(description = "Optional document to upload and parse as context (PDF, TXT, DOCX)")
            @RequestParam(value = "file", required = false) MultipartFile file,

            @Parameter(description = "Optional URL to scrape and inject as context")
            @RequestParam(value = "url", required = false) String url,

            @Parameter(description = "A unique identifier for maintaining conversational state across multiple turns")
            @RequestParam(value = "chatId", required = false) String chatId,

            @Parameter(description = "Target LLM provider engine (e.g., GEMINI, FAST_TIER)")
            @RequestParam(value = "provider", defaultValue = "GEMINI") String provider,

            @Parameter(description = "The absolute maximum token context window allowed for this execution")
            @RequestParam(value = "contextWindow", defaultValue = "8192") int contextWindow,

            @Parameter(description = "If true, returns detailed token optimization and routing telemetry metrics")
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