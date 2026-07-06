package com.example.demo.llmrouter;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatOrchestrationService orchestrationService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AiChatResponse>> processChat(
            @RequestPart("instruction") String instruction,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "contextWindow", defaultValue = "8192") int contextWindow,
            @RequestHeader(value = "X-Developer-Mode", defaultValue = "false") boolean isDevMode) { // <-- DEV MODE HEADER

        return orchestrationService.processRequest(instruction, file, url, contextWindow, isDevMode)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }
}
