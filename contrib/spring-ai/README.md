# ADK Spring AI Integration Library

## Overview

The ADK Spring AI Integration Library provides a bridge between the Agent Development Kit (ADK) and Spring AI, enabling developers to use Spring AI models within the ADK framework. This library supports multiple AI providers, streaming responses, function calling, and comprehensive observability.

## Getting Started

### Maven Dependencies

To use ADK Java with the Spring AI integration in your application, add the following dependencies to your `pom.xml`:

#### Basic Setup

```xml
<dependencies>
    <!-- ADK Core -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk</artifactId>
        <version>0.3.1-SNAPSHOT</version>
    </dependency>

    <!-- ADK Spring AI Integration -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk-spring-ai</artifactId>
        <version>0.3.1-SNAPSHOT</version>
    </dependency>

    <!-- Spring AI BOM for version management -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>1.1.1</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencies>
```

#### Provider-Specific Dependencies

Add the Spring AI provider dependencies for the AI services you want to use:

**OpenAI:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai</artifactId>
</dependency>
```

**Anthropic (Claude):**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-anthropic</artifactId>
</dependency>
```

**Google Gemini:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-google-genai</artifactId>
</dependency>
```

**Vertex AI:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vertex-ai-gemini</artifactId>
</dependency>
```

**Azure OpenAI:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-azure-openai</artifactId>
</dependency>
```

**Ollama (Local models):**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama</artifactId>
</dependency>
```

#### Complete Example pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-adk-spring-ai-app</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.1.1</spring-ai.version>
        <adk.version>0.3.1-SNAPSHOT</adk.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- ADK Dependencies -->
        <dependency>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk</artifactId>
            <version>${adk.version}</version>
        </dependency>
        <dependency>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk-spring-ai</artifactId>
            <version>${adk.version}</version>
        </dependency>

        <!-- Spring AI Providers (choose the ones you need) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-anthropic</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-google-genai</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Quick Start Example

Once you have the dependencies set up, you can create a simple ADK agent with Spring AI:

#### Option 1: Using Auto-Configuration (Recommended)

```java
@SpringBootApplication
public class MyAdkSpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAdkSpringAiApplication.class, args);
    }

    @Bean
    public LlmAgent scienceTeacher(SpringAI springAI) {
        // SpringAI is auto-configured based on available ChatModel beans
        return LlmAgent.builder()
            .name("science-teacher")
            .description("A helpful science teacher")
            .model(springAI)
            .instruction("You are a helpful science teacher. Explain concepts clearly.")
            .build();
    }
}
```

#### Option 2: Manual Configuration

```java
@SpringBootApplication
public class MyAdkSpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAdkSpringAiApplication.class, args);
    }

    @Bean
    public SpringAI springAI() {
        // Configure OpenAI
        OpenAiApi openAiApi = OpenAiApi.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .build();

        return new SpringAI(chatModel, "gpt-4o-mini");
    }

    @Bean
    public LlmAgent scienceTeacher(SpringAI springAI) {
        return LlmAgent.builder()
            .name("science-teacher")
            .description("A helpful science teacher")
            .model(springAI)
            .instruction("You are a helpful science teacher. Explain concepts clearly.")
            .build();
    }
}
```

#### Option 3: Multiple Providers

```java
@SpringBootApplication
public class MyAdkSpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAdkSpringAiApplication.class, args);
    }

    @Bean
    @Primary
    public SpringAI openAiSpringAI() {
        OpenAiApi openAiApi = OpenAiApi.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .build();

        return new SpringAI(chatModel, "gpt-4o-mini");
    }

    @Bean
    @Qualifier("anthropic")
    public SpringAI anthropicSpringAI() {
        AnthropicApi anthropicApi = AnthropicApi.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .build();
        AnthropicChatModel chatModel = AnthropicChatModel.builder()
            .anthropicApi(anthropicApi)
            .build();

        return new SpringAI(chatModel, "claude-3-5-sonnet-20241022");
    }

    @Bean
    public LlmAgent openAiAgent(SpringAI springAI) {
        return LlmAgent.builder()
            .name("openai-teacher")
            .model(springAI) // Uses @Primary SpringAI bean
            .instruction("You are a helpful science teacher using OpenAI.")
            .build();
    }

    @Bean
    public LlmAgent anthropicAgent(@Qualifier("anthropic") SpringAI anthropicSpringAI) {
        return LlmAgent.builder()
            .name("anthropic-teacher")
            .model(anthropicSpringAI) // Uses specific Anthropic SpringAI bean
            .instruction("You are a helpful science teacher using Claude.")
            .build();
    }
}
```

### Configuration

Add these properties to your `application.yml` or `application.properties`:

```yaml
# Spring AI Provider Configuration
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.7
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-3-5-sonnet-20241022
          temperature: 0.7

# ADK Spring AI Configuration
adk:
  spring-ai:
    default-model: "gpt-4o-mini"
    auto-configuration:
      enabled: true
    validation:
      enabled: true
      fail-fast: false
    observability:
      enabled: true
      metrics-enabled: true
```

## Architecture

### Core Components

The library is structured around several key components that work together to provide seamless integration:

```
adk-spring-ai/
├── src/main/java/com/google/adk/models/springai/
│   ├── SpringAI.java                    # Main adapter class
│   ├── SpringAIEmbedding.java           # Embedding model wrapper
│   ├── MessageConverter.java            # Message format conversion
│   ├── ToolConverter.java               # Function/tool conversion
│   ├── ConfigMapper.java                # Configuration mapping
│   ├── autoconfigure/                   # Spring Boot auto-configuration
│   ├── observability/                   # Metrics and logging
│   ├── properties/                      # Configuration properties
│   └── error/                          # Error handling and mapping
```

### Primary Classes

#### 1. SpringAI (SpringAI.java)

The main adapter class that implements `BaseLlm` and wraps Spring AI `ChatModel` and `StreamingChatModel` instances.

**Key Features:**
- Supports both blocking and streaming chat models
- Reactive API using RxJava3 Flowable
- Comprehensive error handling and observability
- Token usage tracking
- Multiple constructor overloads for different scenarios

**Usage:**
```java
// With ChatModel only
SpringAI springAI = new SpringAI(chatModel, "claude-sonnet-4-20250514");

// With both ChatModel and StreamingChatModel
SpringAI springAI = new SpringAI(chatModel, streamingChatModel, "claude-sonnet-4-20250514");

// With observability configuration
SpringAI springAI = new SpringAI(chatModel, "claude-sonnet-4-20250514", observabilityConfig);
```

#### 2. MessageConverter (MessageConverter.java)

Handles conversion between ADK's `Content`/`Part` format and Spring AI's `Message`/`ChatResponse` format.

**Key Features:**
- Converts ADK `LlmRequest` to Spring AI `Prompt`
- Converts Spring AI `ChatResponse` to ADK `LlmResponse`
- Supports system, user, and assistant messages
- Handles function calls and responses
- **Gemini Compatibility:** Combines multiple system messages into one for Gemini API compatibility
- Streaming response detection and partial response handling

**Message Type Mapping:**
- ADK `Content` with role "user" → Spring AI `UserMessage`
- ADK `Content` with role "model"/"assistant" → Spring AI `AssistantMessage`
- ADK `Content` with role "system" → Spring AI `SystemMessage`
- Function calls and responses are converted appropriately

#### 3. ToolConverter (ToolConverter.java)

Converts between ADK tools and Spring AI function calling format.

**Key Features:**
- Converts ADK `BaseTool` to Spring AI `ToolCallback`
- Schema conversion from ADK format to Spring AI JSON schema
- Intelligent argument processing for different provider formats
- **Function Schema Registration:** Properly registers JSON schemas with Spring AI using `inputSchema()` method
- Debug logging for troubleshooting function calling issues

**Function Calling Flow:**
1. ADK `FunctionDeclaration` → Spring AI `FunctionToolCallback`
2. ADK schema → JSON schema string
3. Runtime argument conversion and validation
4. Tool execution and result serialization

#### 4. SpringAIEmbedding (SpringAIEmbedding.java)

Wrapper for Spring AI embedding models providing ADK-compatible embedding generation.

**Key Features:**
- Single text and batch text embedding
- Reactive API using RxJava3 Single
- Full EmbeddingRequest/EmbeddingResponse support
- Observability and error handling
- Dimension information access

#### 5. ConfigMapper (ConfigMapper.java)

Maps ADK `GenerateContentConfig` to Spring AI `ChatOptions`.

**Supported Configurations:**
- Temperature (Float → Double conversion)
- Max output tokens
- Top-P (Float → Double conversion)
- Stop sequences
- Configuration validation

**Unsupported/Provider-Specific:**
- Top-K (not directly supported by Spring AI)
- Presence/frequency penalties (provider-specific)
- Response schema and MIME type

## Modules

### Core Module
- **Package:** `com.google.adk.models.springai`
- **Purpose:** Main integration classes
- **Key Classes:** `SpringAI`, `MessageConverter`, `ToolConverter`, `ConfigMapper`

### Embedding Module
- **Package:** `com.google.adk.models.springai`
- **Purpose:** Embedding model integration
- **Key Classes:** `SpringAIEmbedding`, `EmbeddingConverter`

### Auto-Configuration Module
- **Package:** `com.google.adk.models.springai.autoconfigure`
- **Purpose:** Spring Boot auto-configuration
- **Key Classes:** `SpringAIAutoConfiguration`

### Observability Module
- **Package:** `com.google.adk.models.springai.observability`
- **Purpose:** Metrics, logging, and monitoring
- **Key Classes:** `SpringAIObservabilityHandler`

### Properties Module
- **Package:** `com.google.adk.models.springai.properties`
- **Purpose:** Configuration properties
- **Key Classes:** `SpringAIProperties`

### Error Handling Module
- **Package:** `com.google.adk.models.springai.error`
- **Purpose:** Error mapping and handling
- **Key Classes:** `SpringAIErrorMapper`

## Key Functions

### Chat Generation

```java
// Non-streaming
Flowable<LlmResponse> response = springAI.generateContent(llmRequest, false);

// Streaming
Flowable<LlmResponse> stream = springAI.generateContent(llmRequest, true);
```

### Function Calling

The library supports function calling through ADK tools:

```java
// Create agent with tools
LlmAgent agent = LlmAgent.builder()
    .name("weather-agent")
    .model(springAI)
    .tools(FunctionTool.create(WeatherTools.class, "getWeatherInfo"))
    .build();

// Tools are automatically converted to Spring AI format
```

### Embedding Generation

```java
// Single text embedding
Single<float[]> embedding = springAIEmbedding.embed("Hello world");

// Batch embedding
Single<List<float[]>> embeddings = springAIEmbedding.embed(texts);

// Full request/response
Single<EmbeddingResponse> response = springAIEmbedding.embedForResponse(request);
```

### Configuration Mapping

```java
// ADK config automatically mapped to Spring AI ChatOptions
LlmRequest request = LlmRequest.builder()
    .contents(contents)
    .config(GenerateContentConfig.builder()
        .temperature(0.7f)
        .maxOutputTokens(1000)
        .topP(0.9f)
        .build())
    .build();
```

## Supported Providers

The library works with any Spring AI provider:

### Tested Providers

1. **OpenAI** (`spring-ai-openai`)
   - Models: GPT-4o, GPT-4o-mini, GPT-3.5-turbo
   - Features: Chat, streaming, function calling, embeddings

2. **Anthropic** (`spring-ai-anthropic`)
   - Models: Claude 3.5 Sonnet, Claude 3 Haiku
   - Features: Chat, streaming, function calling
   - **Note:** Requires proper function schema registration

3. **Google Gemini** (`spring-ai-google-genai`)
   - Models: Gemini 2.0 Flash, Gemini 1.5 Pro
   - Features: Chat, streaming, function calling
   - **Note:** Requires single system message (automatically handled)

4. **Vertex AI** (`spring-ai-vertex-ai-gemini`)
   - Models: Vertex AI Gemini models
   - Features: Chat, streaming, function calling

5. **Azure OpenAI** (`spring-ai-azure-openai`)
   - Models: Azure-hosted OpenAI models
   - Features: Chat, streaming, function calling

6. **Ollama** (`spring-ai-ollama`)
   - Models: Local Llama, Mistral, etc.
   - Features: Chat, streaming

### Provider-Specific Considerations

#### Gemini
- **System Messages:** Only one system message allowed - library automatically combines multiple system messages
- **Model Names:** Use `gemini-2.0-flash`, `gemini-1.5-pro`
- **API Key:** Requires `GOOGLE_API_KEY` environment variable

#### Anthropic
- **Function Calling:** Requires explicit schema registration using `inputSchema()` method
- **Model Names:** Use full model names like `claude-3-5-sonnet-20241022`
- **API Key:** Requires `ANTHROPIC_API_KEY` environment variable

#### OpenAI
- **Standard Support:** Full feature compatibility
- **Model Names:** Use `gpt-4o-mini`, `gpt-4o`, etc.
- **API Key:** Requires `OPENAI_API_KEY` environment variable

## Auto-Configuration

The library provides Spring Boot auto-configuration for seamless integration:

### Configuration Properties

```yaml
adk:
  spring-ai:
    default-model: "gpt-4o-mini"
    temperature: 0.7
    max-tokens: 1000
    top-p: 0.9
    top-k: 40
    auto-configuration:
      enabled: true
    validation:
      enabled: true
      fail-fast: false
    observability:
      enabled: true
      metrics-enabled: true
      include-content: false
```

### Auto-Configuration Beans

The auto-configuration creates beans based on available Spring AI models:

```java
@Bean
@ConditionalOnBean({ChatModel.class, StreamingChatModel.class})
public SpringAI springAIWithBothModels(
    ChatModel chatModel,
    StreamingChatModel streamingChatModel,
    SpringAIProperties properties) {
    // Auto-configured SpringAI instance
}

@Bean
@ConditionalOnBean(EmbeddingModel.class)
public SpringAIEmbedding springAIEmbedding(
    EmbeddingModel embeddingModel,
    SpringAIProperties properties) {
    // Auto-configured SpringAIEmbedding instance
}
```

## Integration Testing

The library includes comprehensive integration tests for different providers:

### Test Classes

1. **OpenAiApiIntegrationTest.java**
   - Tests OpenAI integration with real API calls
   - Covers blocking, streaming, and function calling

2. **GeminiApiIntegrationTest.java**
   - Tests Google Gemini integration with real API calls
   - Covers blocking, streaming, and function calling
   - Tests configuration options

3. **MessageConverterTest.java**
   - Unit tests for message conversion logic
   - Tests system message combining for Gemini compatibility

### Running Integration Tests

```bash
# Set required environment variables
export OPENAI_API_KEY=your_key
export GOOGLE_API_KEY=your_key
export ANTHROPIC_API_KEY=your_key

# Run specific integration test
mvn test -Dtest=OpenAiApiIntegrationTest

# Run all tests
mvn test
```

## Error Handling

The library provides comprehensive error handling through `SpringAIErrorMapper`:

### Error Mapping
- Spring AI exceptions → ADK-compatible errors
- Provider-specific error normalization
- Detailed error context preservation

### Observability
- Request/response logging
- Token usage tracking
- Error metrics collection
- Performance monitoring

## Best Practices

### Model Configuration
1. Always specify explicit model names rather than relying on defaults
2. Use environment variables for API keys
3. Configure appropriate timeouts for your use case
4. Enable observability for production monitoring

### Function Calling
1. Ensure function schemas are properly defined in ADK tools
2. Test function calling with each provider separately
3. Handle provider-specific argument format differences
4. Use debug logging to troubleshoot function calling issues

### Performance
1. Use streaming for long responses
2. Implement proper backpressure handling
3. Configure connection pooling for high-throughput scenarios
4. Monitor token usage and costs

### Error Handling
1. Implement retry logic for transient failures
2. Handle provider-specific error conditions
3. Use circuit breakers for external API calls
4. Log errors with sufficient context for debugging

## Dependencies

### Core Dependencies
- Spring AI Model (`spring-ai-model`)
- ADK Core (`google-adk`)
- Google GenAI Types (`google-genai`)
- RxJava3 for reactive programming
- Jackson for JSON processing

### Provider Dependencies (Test Scope)
- `spring-ai-openai`
- `spring-ai-anthropic`
- `spring-ai-google-genai`
- `spring-ai-vertex-ai-gemini`
- `spring-ai-azure-openai`
- `spring-ai-ollama`

### Spring Boot Integration
- `spring-boot-autoconfigure` (optional)
- `spring-boot-configuration-processor` (optional)
- `jakarta.validation-api` (optional)

## Future Enhancements

### Planned Features
1. Enhanced provider-specific optimizations
2. Advanced streaming aggregation
3. Multi-modal content support
4. Enhanced observability and metrics
5. Performance optimization for high-throughput scenarios

### Known Limitations
1. Live connection mode not supported (returns `UnsupportedOperationException`)
2. Some provider-specific features may not be fully supported
3. Response schema and MIME type configuration limited
4. Top-K parameter not directly mapped to Spring AI

## Migration Guide

### From Direct Spring AI Usage
1. Replace Spring AI `ChatModel.call()` with `SpringAI.generateContent()`
2. Update message formats from Spring AI to ADK format
3. Configure auto-configuration properties
4. Update dependency management to include ADK Spring AI

### Version Compatibility
- Spring AI: 1.1.0-M3+
- Spring Boot: 3.0+
- Java: 17+
- ADK: 0.3.1+

This library provides a robust foundation for integrating Spring AI models with the ADK framework, offering enterprise-grade features like observability, error handling, and multi-provider support while maintaining the flexibility and power of both frameworks.