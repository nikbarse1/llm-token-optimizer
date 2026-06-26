# API Examples

This document provides comprehensive examples for using the LLM Token Optimizer API.

## 📚 Table of Contents

- [Token Counter API](#token-counter-api)
- [Document Optimization API](#document-optimization-api)
- [Error Handling](#error-handling)
- [Code Examples](#code-examples)

---

## Token Counter API

### Endpoint
```
POST /api/v1/tokens/count
```

### Basic Example

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/tokens/count \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Hello, how are you today?"
  }'
```

**Response:**
```json
{
  "modelUsed": "gpt-4",
  "tokenCount": 7,
  "characterCount": 26,
  "estimatedCostNote": "1 token is roughly 4 characters in English.",
  "timestamp": "2026-06-26T12:00:00"
}
```

### Long Text Example

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/tokens/count \
  -H "Content-Type: application/json" \
  -d '{
    "text": "The quick brown fox jumps over the lazy dog. This is a longer piece of text that demonstrates how the token counter works with multiple sentences and various punctuation marks!"
  }'
```

**Response:**
```json
{
  "modelUsed": "gpt-4",
  "tokenCount": 42,
  "characterCount": 178,
  "estimatedCostNote": "1 token is roughly 4 characters in English.",
  "timestamp": "2026-06-26T12:05:00"
}
```

### Code Example

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/tokens/count \
  -H "Content-Type: application/json" \
  -d '{
    "text": "function hello() {\n  console.log(\"Hello, World!\");\n}"
  }'
```

**Response:**
```json
{
  "modelUsed": "gpt-4",
  "tokenCount": 16,
  "characterCount": 51,
  "estimatedCostNote": "1 token is roughly 4 characters in English.",
  "timestamp": "2026-06-26T12:10:00"
}
```

---

## Document Optimization API

### Endpoint
```
POST /api/v1/optimize
```

### Basic Example

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "document": "This is a sample document that contains important information about machine learning and artificial intelligence. Machine learning is a subset of AI that focuses on training algorithms to learn from data. Deep learning, a subset of machine learning, uses neural networks with multiple layers.",
    "contextWindow": 8000
  }'
```

**Response:**
```json
{
  "contextWindow": 8000,
  "originalTokens": 52,
  "summaryTokens": 25,
  "reductionPercentage": 51.92,
  "headroomBefore": 7948,
  "headroomAfter": 7975,
  "summary": "• ML: subset of AI, trains algorithms from data\n• Deep learning: ML subset using multi-layer neural networks",
  "timestamp": "2026-06-26T12:15:00"
}
```

### Large Document Example

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "document": "Artificial Intelligence (AI) has revolutionized numerous industries over the past decade. From healthcare to finance, AI applications are transforming how we work and live. Machine learning, a core component of AI, enables systems to learn from data without explicit programming. Deep learning, utilizing neural networks with multiple layers, has achieved remarkable success in image recognition, natural language processing, and game playing. The future of AI holds immense potential, with ongoing research in areas like reinforcement learning, transfer learning, and explainable AI. However, ethical considerations around bias, privacy, and job displacement remain critical challenges that the AI community must address.",
    "contextWindow": 16000
  }'
```

**Response:**
```json
{
  "contextWindow": 16000,
  "originalTokens": 128,
  "summaryTokens": 45,
  "reductionPercentage": 64.84,
  "headroomBefore": 15872,
  "headroomAfter": 15955,
  "summary": "• AI revolutionized industries (healthcare, finance)\n• ML: learns from data w/o explicit programming\n• Deep learning: neural networks, excels in image recog, NLP, gaming\n• Future: reinforcement learning, transfer learning, explainable AI\n• Challenges: bias, privacy, job displacement",
  "timestamp": "2026-06-26T12:20:00"
}
```

### Custom Context Window

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "document": "Your very long document here...",
    "contextWindow": 4096
  }'
```

---

## Error Handling

### Invalid Request - Empty Text

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/tokens/count \
  -H "Content-Type: application/json" \
  -d '{
    "text": ""
  }'
```

**Response (400 Bad Request):**
```json
{
  "timestamp": "2026-06-26T12:25:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Text field is required and cannot be empty",
  "path": "/api/v1/tokens/count"
}
```

### Invalid Request - Missing Field

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "contextWindow": 8000
  }'
```

**Response (400 Bad Request):**
```json
{
  "timestamp": "2026-06-26T12:30:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Document field is required and cannot be empty",
  "path": "/api/v1/optimize"
}
```

### LLM Service Error

**Response (500 Internal Server Error):**
```json
{
  "timestamp": "2026-06-26T12:35:00",
  "status": 500,
  "error": "LLM Service Error",
  "message": "Failed to connect to LLM service",
  "path": "/api/v1/optimize"
}
```

---

## Code Examples

### JavaScript (Fetch API)

```javascript
// Token Counter
async function countTokens(text) {
  const response = await fetch('http://localhost:8080/api/v1/tokens/count', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ text }),
  });
  
  if (!response.ok) {
    throw new Error('Failed to count tokens');
  }
  
  return await response.json();
}

// Usage
countTokens('Hello, world!')
  .then(result => console.log('Token count:', result.tokenCount))
  .catch(error => console.error('Error:', error));

// Document Optimizer
async function optimizeDocument(document, contextWindow = 16000) {
  const response = await fetch('http://localhost:8080/api/v1/optimize', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ document, contextWindow }),
  });
  
  if (!response.ok) {
    throw new Error('Failed to optimize document');
  }
  
  return await response.json();
}

// Usage
optimizeDocument('Long document text...', 8000)
  .then(result => console.log('Reduction:', result.reductionPercentage + '%'))
  .catch(error => console.error('Error:', error));
```

### Python (Requests)

```python
import requests

# Token Counter
def count_tokens(text):
    url = 'http://localhost:8080/api/v1/tokens/count'
    payload = {'text': text}
    
    response = requests.post(url, json=payload)
    response.raise_for_status()
    
    return response.json()

# Usage
result = count_tokens('Hello, world!')
print(f"Token count: {result['tokenCount']}")

# Document Optimizer
def optimize_document(document, context_window=16000):
    url = 'http://localhost:8080/api/v1/optimize'
    payload = {
        'document': document,
        'contextWindow': context_window
    }
    
    response = requests.post(url, json=payload)
    response.raise_for_status()
    
    return response.json()

# Usage
result = optimize_document('Long document text...', 8000)
print(f"Reduction: {result['reductionPercentage']}%")
print(f"Summary: {result['summary']}")
```

### Java (RestTemplate)

```java
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

public class LLMClient {
    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl = "http://localhost:8080/api/v1";
    
    public TokenResponse countTokens(String text) {
        String url = baseUrl + "/tokens/count";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        TokenRequest request = new TokenRequest();
        request.setText(text);
        
        HttpEntity<TokenRequest> entity = new HttpEntity<>(request, headers);
        
        return restTemplate.postForObject(url, entity, TokenResponse.class);
    }
    
    public OptimizationResponse optimizeDocument(String document, int contextWindow) {
        String url = baseUrl + "/optimize";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        OptimizationRequest request = new OptimizationRequest();
        request.setDocument(document);
        request.setContextWindow(contextWindow);
        
        HttpEntity<OptimizationRequest> entity = new HttpEntity<>(request, headers);
        
        return restTemplate.postForObject(url, entity, OptimizationResponse.class);
    }
}

// Usage
LLMClient client = new LLMClient();
TokenResponse tokenResult = client.countTokens("Hello, world!");
System.out.println("Token count: " + tokenResult.getTokenCount());

OptimizationResponse optResult = client.optimizeDocument("Long text...", 8000);
System.out.println("Reduction: " + optResult.getReductionPercentage() + "%");
```

### cURL with Variables

```bash
#!/bin/bash

# Set variables
API_URL="http://localhost:8080/api/v1"
TEXT="Hello, how are you today?"
DOCUMENT="This is a long document that needs to be optimized..."
CONTEXT_WINDOW=16000

# Count tokens
echo "Counting tokens..."
curl -X POST "$API_URL/tokens/count" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"$TEXT\"}" \
  | jq '.'

# Optimize document
echo "Optimizing document..."
curl -X POST "$API_URL/optimize" \
  -H "Content-Type: application/json" \
  -d "{\"document\":\"$DOCUMENT\",\"contextWindow\":$CONTEXT_WINDOW}" \
  | jq '.'
```

---

## Testing with Postman

### Import Collection

You can import these examples into Postman:

1. Create a new collection named "LLM Token Optimizer"
2. Add requests with the examples above
3. Set base URL as a variable: `{{baseUrl}}` = `http://localhost:8080`

### Environment Variables

```json
{
  "baseUrl": "http://localhost:8080",
  "apiVersion": "v1"
}
```

---

## Rate Limiting Considerations

Currently, the API does not implement rate limiting, but when deploying to production, consider:

- Implementing rate limiting per IP or API key
- Setting appropriate timeout values
- Caching frequently requested token counts
- Monitoring API usage

---

## Best Practices

1. **Batch Processing**: For multiple texts, consider batching requests
2. **Error Handling**: Always implement proper error handling
3. **Timeouts**: Set appropriate timeout values for large documents
4. **Caching**: Cache token counts for frequently used texts
5. **Validation**: Validate input before sending to API

---

For more information, see:
- [README.md](README.md) - Full documentation
- [Swagger UI](http://localhost:8080/swagger-ui.html) - Interactive API documentation
- [SETUP.md](SETUP.md) - Setup instructions
