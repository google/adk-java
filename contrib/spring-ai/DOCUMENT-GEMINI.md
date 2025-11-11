# Documentation for the ADK Spring AI Library

## 📖 Overview
The `google-adk-spring-ai` library provides an integration layer between the Google Agent Development Kit (ADK) and the Spring AI project. It allows developers to use Spring AI's `ChatModel`, `StreamingChatModel`, and `EmbeddingModel` as `BaseLlm` and `Embedding` implementations within the ADK framework.

The library handles the conversion between ADK's request/response formats and Spring AI's prompt/chat response formats. It also includes auto-configuration to automatically expose Spring AI models as ADK `SpringAI` and `SpringAIEmbedding` beans in a Spring Boot application.

## 🛠️ Building
To include this library in your project, use the following Maven coordinates:

```xml
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-spring-ai</artifactId>
    <version>0.3.1-SNAPSHOT</version>
</dependency>
```

You will also need to include a dependency for the specific Spring AI model you want to use, for example:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai</artifactId>
</dependency>
```

## 🚀 Usage
The primary way to use this library is through Spring Boot auto-configuration. By including the `google-adk-spring-ai` dependency and a Spring AI model dependency (e.g., `spring-ai-openai`), the library will automatically create a `SpringAI` bean. This bean can then be injected and used as a `BaseLlm` in the ADK.

**Example `application.properties`:**
```properties
# OpenAI configuration
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o-mini
spring.ai.openai.chat.options.temperature=0.7

# ADK Spring AI configuration
adk.spring-ai.model=gpt-4o-mini
adk.spring-ai.validation.enabled=true
```

**Example usage in a Spring service:**
```java
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class MyAgentService {

    private final BaseLlm llm;

    @Autowired
    public MyAgentService(BaseLlm llm) {
        this.llm = llm;
    }

    public Mono<String> generateResponse(String prompt) {
        LlmRequest request = LlmRequest.builder()
            .addText(prompt)
            .build();
        return Mono.from(llm.generateContent(request))
            .map(llmResponse -> llmResponse.content().get().parts().get(0).text().get());
    }
}
```

## 📚 API Reference
### Key Classes
- **`SpringAI`**: The main class that wraps a Spring AI `ChatModel` and/or `StreamingChatModel` and implements the ADK `BaseLlm` interface.
  - **Methods**:
    - `generateContent(LlmRequest llmRequest, boolean stream)`: Generates content, either streaming or non-streaming, by calling the underlying Spring AI model. It converts the ADK `LlmRequest` to a Spring AI `Prompt` and the `ChatResponse` back to an ADK `LlmResponse`.

- **`SpringAIEmbedding`**: Wraps a Spring AI `EmbeddingModel` to be used for generating embeddings within the ADK framework.

- **`SpringAIAutoConfiguration`**: The Spring Boot auto-configuration class that automatically discovers and configures `SpringAI` and `SpringAIEmbedding` beans based on the `ChatModel`, `StreamingChatModel`, and `EmbeddingModel` beans present in the application context.

- **`SpringAIProperties`**: A configuration properties class (`@ConfigurationProperties("adk.spring-ai")`) that allows for customization of the Spring AI integration.
  - **Properties**:
    - `model`: The model name to use.
    - `validation.enabled`: Whether to enable configuration validation.
    - `validation.fail-fast`: Whether to fail fast on validation errors.
    - `observability.enabled`: Whether to enable observability features.
