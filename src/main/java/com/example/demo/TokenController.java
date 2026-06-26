package com.example.demo;

import com.example.demo.exception.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
@Tag(name = "Token Counter", description = "APIs for counting tokens in text using OpenAI tokenization")
public class TokenController {

    private final OpenAiTokenService tokenService;

    @PostMapping("/count")
    @Operation(summary = "Count tokens in text", 
               description = "Counts the number of tokens in the provided text using OpenAI's tokenization algorithm")
    public ResponseEntity<TokenResponse> countTokens(@Valid @RequestBody TokenRequest request) {
        log.info("Received token count request for text of length: {}", 
                request.getText() != null ? request.getText().length() : 0);
        
        if (request.getText() == null || request.getText().isBlank()) {
            throw new InvalidRequestException("Text field is required and cannot be empty");
        }

        TokenResponse response = tokenService.countTokens(request);
        log.info("Token count completed: {} tokens for {} characters", 
                response.getTokenCount(), response.getCharacterCount());
        return ResponseEntity.ok(response);
    }
}
