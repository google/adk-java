# adk-model-prism-ollama

Ollama provider JAR for Google ADK Java.
Runs models locally - no API key, no
network, no cost. Drop this on the 
classpath and `OllamaModelProvider` 
auto-registers via `META-INF/services`.

---

## Usage

Add the dependency and call `ModelProviderRegistry.registerAll()` 
once at startup:

```xml
<dependency>
	<groupId>com.google.adk</groupId>
	<artifactId>adk-model-prism-ollama</artifactId>
	<version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
ModelProviderRegistry.registerAll();
```

Then use any pulled Ollama model in your agents:

```yaml
# agent.yaml
model: ollama/llama3
```

## Setup

1. Install Ollama: https://ollama.com
2. Pull a model:
```bash
ollama pull llama3
```
---

## Configuration

| Environment variable | Required | Description |
|---|---|---|
|`OLLAMA_BASE_URL` | `http://localhost:11434` | Override if Ollama is on a different host/port |

To use a remote Ollama instance:
```
OLLAMA_BASE_URL=http://localhost:11434
```
No API key is required. The `Authorization` header is omitted entirely.

---

## Model Names

Prefix any Ollama model name with `ollama/`: 

| Model | Notes |
|---|---|
|`ollama/llama3` | `ollama pull llama3` |
|`ollama/mistral` | `ollama pull mistral` |
|`ollama/codellama` | `ollama pull codellama` |
|`ollama/phi3` | `ollama pull phi3` |

Full model library: https://ollama.com/library

>**Note:** Tool calling support varies by model. 
>Larger models (13B+) are generally more reliable
>with structured tool calls. Smaller models may 
>ignore or misformat them.
---

## How It Works

This JAR contains a single `META-INF/services` entry:

```
META-INF/services/com.google.adk.models.spi.ModelProvider
	|--com.google.adk.models.ollama.OllamaModelProvider
```

When `ModelProviderRegistry.registerAll()` runs, `ServiceLoader` finds 
this entry and registers pattern `ollama/.*` with `LlmRegistry`,
`OllamaModelProvider` delegates all HTTP and JSON work to 
`OpenAiCompatibleLlm` in the `adk-model-prism-core` module.
