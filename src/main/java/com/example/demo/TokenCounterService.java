package com.example.demo;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.springframework.stereotype.Service;

@Service
public class TokenCounterService {

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    // cl100k_base is extremely efficient. Other models often yield higher token counts.
    private final Encoding encoding = registry.getEncodingForModel(ModelType.GPT_4);

    /**
     * Standard OpenAI-based token count. Good for baseline routing heuristics.
     */
    public int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * Provider-aware token counting. Applies heuristic multipliers to prevent context
     * window crashes for models that do not use the standard OpenAI tokenizer.
     */
    public int countTokens(String text, String providerName) {
        int baseCount = countTokens(text);

        if ("GEMINI".equalsIgnoreCase(providerName)) {
            // Gemini's SentencePiece tokenizer typically generates ~20% more tokens
            // than OpenAI for the same text, especially for source code.
            return (int) Math.ceil(baseCount * 1.20);
        }

        // Return standard count for FAST_TIER models (assuming they use Llama/GPT tokenizers)
        return baseCount;
    }
}