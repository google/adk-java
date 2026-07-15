# ADK LangChain4j Integration Library

## Overview

The ADK LangChain4j Integration library provides a bridge between the Agent Development Kit (ADK) and 
[LangChain4j](https://docs.langchain4j.dev/). 
The main purpose of this module is to allow ADK to have access to all the LLM providers supported 
by the LangChain4j ecosystem (e.g., Anthropic, OpenAI, Ollama, Google Gemini, and many more). 

This library supports multiple AI providers, function calling (tools), streaming responses, 
and automatically maps ADK models and mechanisms to their LangChain4j counterparts.

## Getting Started

### Maven Dependencies

To use ADK Java with the LangChain4j integration in your application, 
add the following dependencies to your `pom.xml`:

#### Basic Setup

```xml
<dependencies>
    <!-- ADK Core -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk</artifactId>
        <version>1.0.0</version> <!-- Use the most recent ADK version -->
    </dependency>

    <!-- ADK LangChain4j Integration -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk-langchain4j</artifactId>
        <version>1.0.0</version> <!-- Use the most recent ADK version -->
    </dependency>
</dependencies>
```

#### Provider-Specific Dependencies

You'll also need to add the LangChain4j provider dependencies for the AI services you want to use.
Refer to the [full list](https://docs.langchain4j.dev/category/language-models)
of supported models that you want to use in your project.

**Anthropic**  (Claude):
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
</dependency>
```

**OpenAI** and compatible models:
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>
```

**Ollama** (local models):
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-ollama</artifactId>
</dependency>
```

*(You can use any other `langchain4j-*` module supported by LangChain4j).*

## Quick Start Examples

Once you have the dependencies set up, you can create a simple ADK agent using any LangChain4j chat model. 
You just need to wrap the `ChatModel` into the ADK `LangChain4j` model builder.

### Anthropic Example

```java
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.anthropic.AnthropicChatModel;

// 1. Initialize LangChain4j model
AnthropicChatModel claudeModel = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4-6")
    .build();

// 2. Wrap the LangChain4j model for ADK
LangChain4j adkModel = LangChain4j.builder()
    .chatModel(claudeModel)
    .modelName("claude-sonnet-4-6")
    .build();

// 3. Create your agent
LlmAgent agent = LlmAgent.builder()
    .name("science-teacher")
    .description("A helpful science teacher")
    .model(adkModel)
    .instruction("You are a helpful science teacher that explains concepts clearly.")
    .build();
```

### OpenAI Example

```java
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.openai.OpenAiChatModel;

// 1. Initialize LangChain4j model
OpenAiChatModel gptModel = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o-mini")
    .build();

// 2. Wrap the LangChain4j model for ADK
LangChain4j adkModel = LangChain4j.builder()
    .chatModel(gptModel)
    .modelName("gpt-4o-mini")
    .build();

// 3. Create your agent
LlmAgent agent = LlmAgent.builder()
    .name("friendly-assistant")
    .description("A friendly assistant")
    .model(adkModel)
    .instruction("You are a friendly assistant.")
    .build();
```

### Ollama Example (Local Models)

```java
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.ollama.OllamaChatModel;

// 1. Initialize LangChain4j model for Ollama
OllamaChatModel ollamaModel = OllamaChatModel.builder()
    .baseUrl("http://localhost:11434")
    .modelName("llama3")
    .build();

// 2. Wrap the LangChain4j model for ADK
LangChain4j adkModel = LangChain4j.builder()
    .chatModel(ollamaModel)
    .modelName("llama3")
    .build();

// 3. Create your agent
LlmAgent agent = LlmAgent.builder()
    .name("local-agent")
    .description("A local assistant running on Ollama")
    .model(adkModel)
    .instruction("You are an assistant running locally.")
    .build();
```

## Advanced Usage

### Streaming Responses

The integration fully supports streaming models from LangChain4j using the `streamingChatModel` property of the `LangChain4j` builder. 
Make sure to use a `StreamingChatModel` instead of a regular `ChatModel` from LangChain4j.

```java
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

// 1. Initialize a LangChain4j STREAMING chat model
AnthropicStreamingChatModel streamingModel = AnthropicStreamingChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4-6")
    .build();

// 2. Wrap it for ADK using `streamingChatModel` config
LangChain4j adkStreamingModel = LangChain4j.builder()
    .streamingChatModel(streamingModel)
    .modelName("claude-sonnet-4-6")
    .build();

// 3. Create your agent as usual
LlmAgent agent = LlmAgent.builder()
    .name("streaming-agent")
    .model(adkStreamingModel)
    .instruction("You answer questions as fast as possible.")
    .build();
```

### Function Calling (Tools)

ADK tools are automatically mapped to LangChain4j tools under the hood. 
You can configure them as you usually do in ADK:

```java
LlmAgent agent = LlmAgent.builder()
    .name("weather-agent")
    .model(adkModel)
    .instruction("If asked about weather, you MUST call the `getWeather` function.")
    .tools(FunctionTool.create(ToolExample.class, "getWeather"))
    .build();
```
