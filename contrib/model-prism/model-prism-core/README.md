# adk-model-prism-core

The core ModelPrism module for pluggable LLM backends in Google ADK Java via the Java ServiceLoader SPI pattern.
This is the module proposed for contribution to `google-adk.jar`

---

## Contents

| Class | Role |
|---|---|
| `ModelProvider` | SPI interface every provider implements |
| `ModelProviderRegistry` | ServiceLoader wiring - calls `LlmRegistry.registerLlm()` for each discovered provider |
| `OpenAiCompatibleLlm` | Reusable `BaseLlm` base class for any OpenAI-format REST API |
| `OpenAiHttpClient` | HTTP transport interface |
| `DefaultOpenAiHttpClient` | `java.net.http` implementation of `OpenAiHttpClient` |
| `OpenAiMessageSerializer` | JSON serialization interface | 
| `DefaultOpenAiMessageSerializer` | Jackson implementation of `OpenAiMessageSerializer` |

---

## `ModelProvider` - the SPI (Service Provider Interface)

```java
public interface ModelProvider {

  /**
   * Regex matched against the agent's model string, e.g. "groq/.*" */
  String modelPattern();

  /** Factory - called by ADK when an agent uses a matching model name.   */
  BaseLlm create(String modelName);
}
```

This is the only interface a provider author needs to implement.
Convention: prefix the pattern with the provider name followed by `/`
(e.g. `"groq/.*"`, `"ollama/.*"`) to avoid collisions.

---

## `ModelProviderRegistry` - ServiceLoader wiring

```java
// Discovers and registers all providers on the classpath
List<ModelProvider> registered = ModelProviderRegistry.registerAll();

// Overload for custom ClassLoader 
List<ModelProvider> registered = ModelProviderRegistry.registerAll(myClassLoader);
```

Iterates every `META-INF/services/com.google.adk.models.spi.ModelProvider` file
found on the classpath and calls `LlmRegistry.registerLlm(pattern, factory)` for each entry.

**Proposed ADK integration point:** 

First integration point: `Runner` in `google-adk.jar`

```java
// Runner constructor - called by every ADK application
public Runner(BaseAgent agent, String appName, ...){
	ModelProviderRegistry.registerAll(); // <- one line
```

By calling `registerAll()` once inside `Runner`, **every ADK application**
gets automatic provider discovery with no code changes, regardless of the deployment pattern.

Second integration point: `AdkWebServer.start()` in `google-adk-dev`
would call `registerAll()` once, so application code never needs to call it directly.

```java
ModelProviderRegistry.registerAll();
```

---

## `OpenAiCompatibleLlm` - base class for OpenAI-format APIs

Groq, Ollama, OpenRouter, Together AI, Fireworks, and many others all expose
the same `POST /v1/chat/completions` REST interface. A new provider
needs only a one-liner subclass:

```java
public class GroqLlm extends OpenAiCompatibleLlm {
	public GroqLlm(String modelName, Optional<String> apiKey) {
		super(modelName, "https://api.groq.com/openai/v1/chat/completions", apiKey);
	}
}
```

Constructor parameters:
- `modelName` - bare model name sent to the API (e.g. `llama-3.1-8b-instant`)
- `apiUrl` - full endpoint URL
- `apiKey` - bearer token, or `Optional.empty()` for keyless providers like Ollama

A protected injection constructor is available for testing with custom collaborators:

```java
protected OpenAiCompatibleLlm(String modelName, 
									OpenAiHttpClient httpClient,
									OpenAiMessageSerializer serializer)
```

**Supported features:**

| Feature | Details |
|---|---|
| System instructions | Mapped to `{"role":"system"}` message |
| Conversation history | ADK `"model"` role -> OpenAI `"assistant"` |
| Tool declarations | `FunctionDeclaration` -> OpenAI function schema |
| Tool call responses | `tool_calls` in response -> `Part.fromFunctionCall(...)` |
| Function response history | `Part.functionResponse()` -> `role=tool` messages |
| Non-streaming | `generateContent(request, false)` - single `LlmResponse` |
| SSE token streaming | `generateContent(request, true)` - `Flowable` of partial responses |

---

## Dependency

```xml
<dependency>
	<groupId>com.google.adk</groupId>
	<artifactId>adk-model-prism-core</artifactId>
	<version>0.1.0-SNAPSHOT</version>
</dependency>
```
