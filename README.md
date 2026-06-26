# 🚀 LLM Token Optimizer

A Spring Boot application that helps developers optimize text for Large Language Model (LLM) context windows by counting tokens and intelligently summarizing documents.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 📋 Table of Contents

- [Features](#-features)
- [Architecture](#-architecture)
- [Prerequisites](#-prerequisites)
- [Quick Start](#-quick-start)
- [API Documentation](#-api-documentation)
- [Configuration](#-configuration)
- [Docker Deployment](#-docker-deployment)
- [Development](#-development)
- [Testing](#-testing)
- [Contributing](#-contributing)
- [License](#-license)

## ✨ Features

- **Token Counting**: Accurately count tokens using OpenAI's tokenization algorithm (jtokkit)
- **Document Optimization**: Automatically summarize long documents to fit within LLM context windows
- **Multiple LLM Support**: Integrates with Groq API (supports Llama, Mixtral models)
- **Fallback Mechanism**: Local extractive summarization when API is unavailable
- **Interactive UI**: Beautiful web interface for testing the API
- **RESTful API**: Well-documented endpoints with Swagger/OpenAPI
- **Docker Ready**: Containerized deployment with Docker Compose
- **Production Ready**: Comprehensive error handling, logging, and validation

## 🏗️ Architecture

```
┌─────────────┐
│   Client    │
│  (Browser)  │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────┐
│      Spring Boot Application        │
│  ┌───────────────────────────────┐  │
│  │   Controllers                 │  │
│  │  - TokenController            │  │
│  │  - TokenOptimizationController│  │
│  └───────────┬───────────────────┘  │
│              │                       │
│  ┌───────────▼───────────────────┐  │
│  │   Services                    │  │
│  │  - OpenAiTokenService         │  │
│  │  - TokenCounterService        │  │
│  │  - LLMSummarizationService    │  │
│  │  - TokenOptimizationService   │  │
│  └───────────┬───────────────────┘  │
│              │                       │
└──────────────┼───────────────────────┘
               │
       ┌───────┴────────┐
       │                │
       ▼                ▼
┌─────────────┐  ┌──────────────┐
│   jtokkit   │  │  Groq API    │
│  (Offline)  │  │  (Online)    │
└─────────────┘  └──────────────┘
```

## 📦 Prerequisites

- **Java 21** or higher
- **Maven 3.9+**
- **Docker** (optional, for containerized deployment)
- **Groq API Key** (free at [console.groq.com](https://console.groq.com/keys))

## 🚀 Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/demo-for-llm.git
cd demo-for-llm
```

### 2. Set Up Environment Variables

Create a file named `application-local.properties` in `src/main/resources/`:

```properties
llm.api.key=your-groq-api-key-here
llm.model=llama-3.1-8b-instant
```

Or set environment variables:

```bash
export LLM_API_KEY=your-groq-api-key-here
export LLM_MODEL=llama-3.1-8b-instant
```

### 3. Build and Run

```bash
# Build the project
mvn clean install

# Run the application
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

### 4. Access the Application

- **Web UI**: http://localhost:8080
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/v3/api-docs

## 📚 API Documentation

### Token Counter API

**Endpoint**: `POST /api/v1/tokens/count`

**Request**:
```json
{
  "text": "Hello, how are you today?",
  "model": "gpt-4"
}
```

**Response**:
```json
{
  "modelUsed": "gpt-4",
  "tokenCount": 7,
  "characterCount": 26,
  "estimatedCostNote": "1 token is roughly 4 characters in English.",
  "timestamp": "2026-06-26T12:00:00"
}
```

**cURL Example**:
```bash
curl -X POST http://localhost:8080/api/v1/tokens/count \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello, how are you today?"}'
```

### Document Optimization API

**Endpoint**: `POST /api/v1/optimize`

**Request**:
```json
{
  "document": "Your long document text here...",
  "contextWindow": 16000
}
```

**Response**:
```json
{
  "contextWindow": 16000,
  "originalTokens": 5000,
  "summaryTokens": 500,
  "reductionPercentage": 90.0,
  "headroomBefore": 11000,
  "headroomAfter": 15500,
  "summary": "Summarized content...",
  "timestamp": "2026-06-26T12:00:00"
}
```

**cURL Example**:
```bash
curl -X POST http://localhost:8080/api/v1/optimize \
  -H "Content-Type: application/json" \
  -d '{"document":"Long text here...","contextWindow":16000}'
```

## ⚙️ Configuration

### Application Properties

| Property | Description | Default |
|----------|-------------|---------|
| `llm.api.key` | Groq API key | Required |
| `llm.model` | LLM model to use | `llama-3.1-8b-instant` |
| `server.port` | Server port | `8080` |

### Supported Models

- `llama-3.1-8b-instant` (Fast, recommended)
- `llama-3.3-70b-versatile` (More accurate)
- `mixtral-8x7b-32768` (Large context window)

## 🐳 Docker Deployment

### Using Docker Compose (Recommended)

```bash
# Set your API key
export LLM_API_KEY=your-api-key-here

# Start the application
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the application
docker-compose down
```

### Using Docker Directly

```bash
# Build the image
docker build -t llm-token-optimizer .

# Run the container
docker run -d -p 8080:8080 \
  -e LLM_API_KEY=your-api-key-here \
  -e LLM_MODEL=llama-3.1-8b-instant \
  --name llm-optimizer \
  llm-token-optimizer
```

## 💻 Development

### Project Structure

```
demo-for-llm/
├── src/
│   ├── main/
│   │   ├── java/com/example/demo/
│   │   │   ├── config/           # Configuration classes
│   │   │   ├── exception/        # Custom exceptions & handlers
│   │   │   ├── *Controller.java  # REST controllers
│   │   │   ├── *Service.java     # Business logic
│   │   │   └── *.java            # DTOs and models
│   │   └── resources/
│   │       ├── static/           # Frontend files
│   │       └── application.properties
│   └── test/                     # Unit tests
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=TokenCounterServiceTest

# Run with coverage
mvn clean test jacoco:report
```

### Code Quality

```bash
# Check code style
mvn checkstyle:check

# Analyze dependencies
mvn dependency:analyze

# Security scan
mvn dependency-check:check
```

## 🧪 Testing

### Manual Testing with cURL

**Test Token Counter**:
```bash
curl -X POST http://localhost:8080/api/v1/tokens/count \
  -H "Content-Type: application/json" \
  -d '{
    "text": "The quick brown fox jumps over the lazy dog"
  }'
```

**Test Document Optimizer**:
```bash
curl -X POST http://localhost:8080/api/v1/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "document": "This is a very long document that needs to be summarized...",
    "contextWindow": 8000
  }'
```

### Using the Web UI

1. Navigate to http://localhost:8080
2. Use the **Token Counter** tab to count tokens
3. Use the **Document Optimizer** tab to summarize documents

## 🎯 Use Cases

### 1. Token Budget Management
Calculate exact token counts before sending requests to LLM APIs to manage costs.

### 2. Context Window Optimization
Automatically compress long documents to fit within model context limits (e.g., GPT-4's 8K/32K tokens).

### 3. Cost Estimation
Estimate API costs based on token counts for different LLM providers.

### 4. Prompt Engineering
Optimize prompts to maximize information while minimizing token usage.

### 5. Document Preprocessing
Prepare documents for RAG (Retrieval-Augmented Generation) systems.

## 🔧 Troubleshooting

### API Key Issues

**Problem**: `LLM service error` or `401 Unauthorized`

**Solution**: 
- Verify your Groq API key is correct
- Check environment variable is set: `echo $LLM_API_KEY`
- Ensure API key has proper permissions

### Port Already in Use

**Problem**: `Port 8080 is already in use`

**Solution**:
```bash
# Change port in application.properties
server.port=8081

# Or use environment variable
export SERVER_PORT=8081
```

### Docker Build Fails

**Problem**: Docker build fails with memory error

**Solution**:
```bash
# Increase Docker memory limit
docker build --memory=4g -t llm-token-optimizer .
```

## 🤝 Contributing

Contributions are welcome! Here's how you can help:

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing-feature`)
5. **Open** a Pull Request

### Development Guidelines

- Follow Java code conventions
- Add unit tests for new features
- Update documentation
- Ensure all tests pass
- Keep commits atomic and well-described

## 📖 Learning Resources

This project demonstrates:

- ✅ Spring Boot REST API development
- ✅ Integration with external LLM APIs
- ✅ Token counting for cost optimization
- ✅ Reactive programming with WebFlux
- ✅ Error handling and validation
- ✅ Docker containerization
- ✅ CI/CD with GitHub Actions
- ✅ API documentation with Swagger/OpenAPI

## 🔮 Future Enhancements

- [ ] Support for more LLM providers (OpenAI, Anthropic, etc.)
- [ ] Batch processing API
- [ ] Token cost calculator with pricing
- [ ] Multiple summarization strategies
- [ ] Streaming responses for large documents
- [ ] Rate limiting and caching
- [ ] User authentication
- [ ] Metrics and monitoring dashboard

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [jtokkit](https://github.com/knuddelsgmbh/jtokkit) - OpenAI tokenizer for Java
- [Groq](https://groq.com/) - Fast LLM inference API
- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework

## 📧 Contact

For questions or support, please open an issue on GitHub.

---

**Made with ❤️ for the LLM community**
