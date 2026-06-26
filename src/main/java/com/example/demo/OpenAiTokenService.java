package com.example.demo;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.springframework.stereotype.Service;

@Service
public class OpenAiTokenService {

    // Initialize the registry once (thread-safe)
    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = registry.getEncodingForModel(ModelType.GPT_4);

    public TokenResponse countTokens(TokenRequest request) {
        String text = request.getText();

        if (text == null || text.isBlank()) {
            return TokenResponse.builder()
                    .modelUsed(ModelType.GPT_4.getName())
                    .tokenCount(0)
                    .characterCount(0)
                    .estimatedCostNote("Empty string")
                    .build();
        }

        // Count tokens offline using OpenAI's algorithm
        int tokenCount = encoding.countTokens(text);
        int charCount = text.length();

        return TokenResponse.builder()
                .modelUsed(ModelType.GPT_4.getName())
                .tokenCount(tokenCount)
                .characterCount(charCount)
                .estimatedCostNote("1 token is roughly 4 characters in English.")
                .build();
    }
}
