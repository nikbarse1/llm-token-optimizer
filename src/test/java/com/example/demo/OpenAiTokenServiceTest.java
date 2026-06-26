package com.example.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiTokenServiceTest {

    private OpenAiTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new OpenAiTokenService();
    }

    @Test
    void testCountTokens_withValidRequest() {
        TokenRequest request = new TokenRequest();
        request.setText("Hello, world!");
        
        TokenResponse response = tokenService.countTokens(request);
        
        assertNotNull(response);
        assertEquals("gpt-4", response.getModelUsed());
        assertTrue(response.getTokenCount() > 0);
        assertEquals(13, response.getCharacterCount());
        assertNotNull(response.getEstimatedCostNote());
    }

    @Test
    void testCountTokens_withEmptyText() {
        TokenRequest request = new TokenRequest();
        request.setText("");
        
        TokenResponse response = tokenService.countTokens(request);
        
        assertNotNull(response);
        assertEquals(0, response.getTokenCount());
        assertEquals(0, response.getCharacterCount());
        assertEquals("Empty string", response.getEstimatedCostNote());
    }

    @Test
    void testCountTokens_withNullText() {
        TokenRequest request = new TokenRequest();
        request.setText(null);
        
        TokenResponse response = tokenService.countTokens(request);
        
        assertNotNull(response);
        assertEquals(0, response.getTokenCount());
        assertEquals(0, response.getCharacterCount());
    }
}
