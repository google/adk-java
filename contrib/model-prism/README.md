# adk-model-prism

A proposal for pluggable LLM backends in [Google ADK Java](https://github.com/google/adk-java)
via the Java **ServiceLoader** SPI pattern.

---

## Scope

This project supports OpenAI-compatible REST providers for **text-based** LLM interaction.
All covered providers expose the same `POST /v1/chat/completions` HTTP endpoint.

| Supported | Not supported |
|---|---|
| Text chat (non-streaming) | Audio generation / transcription |
| SSE token streaming | Image generation |
| Function / tool calling (`FunctionTool`) | Video generation |
| Any OpenAI-compatible REST endpoint | Live / bidirectional streaming (WebSocket, gRPC) |

---

## Context: What ADK Already Provides

Google ADK Java ships with built-in support for **Gemini** models.
For all other providers ADK offers an official option:

### LangChain4j bridge (`contrib/langchain4j`)

The `google-adk-langchain4j` contrib module wraps any [LangChain4j `ChatModel`](https://docs.langchain4j.dev/category/language-models)
as an ADK `BaseLlm`. This covers Anthropic, OpenAI, Ollama, Mistral, Cohere, and many others - no HTTP or serialization code to write:

```java
 // Add google-adk-langchain4j + langchain4j-anthropic to pom.xml, then:
AnthropicChatModel claude = AnthropicChatModel.builder()
	.apiKey(System.getenv("ANTHROPIC_API_KEY"))
	.modelName("claude-sonnet-4-6")
	.build();
	
LlmAgent agent = LlmAgent.builder()
	.name("my-agent")
	.model(LangChain4j.builder().chatModel(claude).modelName("claude-sonnet-4-6").build())
	.build();
```
---

## The Problem This Project Addresses

The LangChain4j bridge solves the implementation boilerplate, but every application
that uses a non-Gemini model must still:

1. **Import** the specific provider library (`langchain4j-anthropic`, `langchain4j-ollama`, ...)
2. **Construct** the provider object explicitly in code
3. **Pass** a `LangChain4j` wrapper object to `.model()` - the model name string 
   `"groq/llama3-70b-8192"` cannot be used; there is no conversion-based discovery
   
Adding or swapping a provider always requires code changes in every application.

## The Proposed Solution 

Introduce a `ModelProvider` SPI and a `ModelProviderRegistry` that uses `java.util.ServiceLoader`
to discover all providers on the classpath automatically:

```java
// Proposed approach - zero provider knowledge in App.java
ModelProviderRegistry.registerAll();
```

Drop a provider JAR on the classpath -> it auto-registers and model name strings like
`"groq/llama3-70b-8192"` resolve automatically. Remove the JAR -> it's gone.
No code changes ever.

### Comparison with the LangChain4j bridge

| | LangChain4j bridge | This project |
|---|---|---|
| Status | Official ADK contrib | Prototype / proposal |
| Provider coverage | Any LangChain4j provider (Anthropic, OpenAI, Mistral, ...) | OpenAI-compatible REST only (Groq, Ollama, OpenRouter, ...) |
| Registration | Manual - construct model object per agent | Automatic via `ServiceLoader` |
| Model name string in `.model()` | pass object, not string | `"groq/llama3-70b-8192"` |
| App imports provider class | Yes | No |
| Code changes to add a provider | Yes | Add one Maven dependency |

The two approaches are **complementary**. Use the LangChain4j bridge for providers outside the OpenAI-compatible REST space (Anthropic, Cohere, etc.);
use this proposed solution for the common case where you want zero-boilerplate model name strings and classpath-driven discovery. 



---

## Project Structure

| Module |  |
|---|---|
| model-prism/ | parent pom |
| model-prism-core/ | ModelProvider SPI + OpenAI base (-> ADK core)|
| model-prism-groq/ | Groq JAR (its own META-INF/services) |
| model-prism-ollama/ | Ollama JAR (its own META-INF/services) |
| model-prism-openrouter/ | OpenRouter JAR (its own META-INF/services)|
| model-prism-demo/	| DemoApp - zero provider wiring code |


Each provider is its own Maven artifact. The demo depends only on `adk-model-prism-core` 
plus whichever provider JARs are listed as dependencies - no provider class is ever imported 
in `DemoApp`.

| Module | Artifact | README |
|---|---|---|
| `model-prism-core` | `adk-model-prism-core` | [model-prism-core/README.md](model-prism-core/README.md) |
| `model-prism-groq` | `adk-model-prism-groq` | [model-prism-groq/README.md](model-prism-groq/README.md) |
| `model-prism-ollama` | `adk-model-prism-ollama` | [model-prism-ollama/README.md](model-prism-ollama/README.md) |
| `model-prism-openrouter` | `adk-model-prism-openrouter` | [model-prism-openrouter/README.md](model-prism-openrouter/README.md) |
| `model-prism-demo` | `adk-model-prism-demo` | [model-prism-demo/README.md](model-prism-demo/README.md) |

---

## Value Proposition

ADK's orchestration layer (`LoopAgent`, `ParallelAgent`, `SequentialAgent`, tool calling, session management)
is built on `BaseLlm` and does not care which model is underneath.
By implementing `BaseLlm` correctly, any provider gets the full ADK feature set for free:

```
LoopAgent / ParallelAgent / SequentialAgent
    |
 ADK orchestration (session, memory, tool loop)
    |
  BaseLlm <- the only contract ADK cares about
    |
 OpenAiCompatibleLlm <- this project (model-prism-core)
    |
 Groq / Ollama / OpenRouter / DeepSeek / ...
```

---

## Built-in Providers

### Groq - fast hosted interface, free tier

```yaml
model: groq/llama-3.1-8b-instant
```
Requires `GROQ_API_KEY`. Sign up: https://console.groq.com
See [model-prism-groq/README.md](model-prism-groq/README.md) for model list.

### Ollama - local models, no API key, no cost

```yaml
model: ollama/llama3
```
Requires Ollama running locally. Install: https://ollama.com
See [model-prism-ollama/README.md](model-prism-ollama/README.md) for setup.

### OpenRouter - hundreds of models, many free tiers

```yaml
model: openrouter/auto
```
Requires `OPENROUTER_API_KEY`. Sign up: https://openrouter.ai
See [model-prism-openrouter/README.md](model-prism-openrouter/README.md) for model list.

---

## Other Compatible Providers

Any service that speaks `POST /v1/chat/completions` works as a one-liner subclass of 
`OpenAiCompatibleLlm` from `adk-model-prism-core`.

## Hosted interface services

| Provider | Base URL | API Key Env Var |
|---|---|---|
| Together AI | `https://api.together.xyz/v1/chat/completions` | `TOGETHER_API_KEY` |
| Fireworks AI | `https://api.fireworks.ai/interface/v1/chat/completions` | `FIREWORKS_API_KEY` |
| Perplexity | `https://api.perplexity.ai/chat/completions` | `PERPLEXITY_API_KEY` |
| Mistral AI | `https://api.mistral.ai/v1/chat/completions` | `MISTRAL_API_KEY` |
| Cerebras | `https://api.cerebras.ai/v1/chat/completions` | `CEREBRAS_API_KEY` |
| DeepSeek | `https://api.deepseek.com/v1/chat/completions` | `DEEPSEEK_API_KEY` |
| xAI (Grok) | `https://api.x.ai/v1/chat/completions` | `XAI_API_KEY` |

### Local / self-hosted

| Provider | Default Base Url | API Key |
|---|---|---|
| LM Studio | `http://localhost:1234/v1/chat/completions` | none |
| llama.cpp server | `http://localhost:8080/v1/chat/completions` | none |
| vLLM | `http://localhost:8000/v1/chat/completions` | none |

### Cloud providers via compatibility layer

| Provider | Notes |
|---|---|
| Azure OpenAI | Different URL shape, same JSON format |
| AWS Bedrock | Via OpenAI-compact endpoint |
| Vertex AI (Google) | `v1beta1/openai` compatibility endpoint |

---

## How to Add a New Provider

1. Implement `ModelProvider` (in a new Maven module or your own JAR):

```java
public class MyProviderModelProvider implements ModelProvider {

  private static final String PREFIX = "myprovider/";
  private static final String API_URL = "https://api.myprovider.com/v1/chat/completions";

  @Override public String modelPattern() {return "myprovider/.*";}

  @Override public BaseLlm create(String modelName) {
    Optional<String> apiKey = Optional.ofNullable(System.getenv("MY_PROVIDER_API_KEY"));
    String model = modelName.startsWith(PREFIX) ? modelName.substring(PREFIX.length()) : modelName;
    return new OpenAiCompatibleLlm(model, API_URL, apiKey);
  }
}
```

2. Register it for ServiceLoader:

```
src/main/resources/META-INF/services/com.google.adk.models.spi.ModelProvider
```
Content:

```
com.example.myprovider.MyProviderModelProvider
```

3. That's it. Drop the JAR on the classpath - agents can use: `model: myprovider/some-model`.

---

## Tool calling

ADK owns the multi-turn tool loop:

```
User prompt
 - ADK sends request + tool declaration to LLM
 - LLM responds with a tool_call
 - ADK executes the Java method
 - ADK sends result back to LLM as a tool response
 - LLM produces the final text answer
```

`DefaultOpenAiMessageSerializer` handles the wire-format translation at both ends.
The same `FunctionTool` works on Groq, Ollama, OpenRouter, or any other provider.

**MCP toolsets** (`MCPToolset`) also work with any provider that supports the OpenAI
function-calling format. ADK connects to the MCP server (via stdio or SSE), discovers
its tool schemas, and forwards tool calls exactly as it does for `FunctionTool`.

> **Note:** Tool calling support varies by model. 70B+ models are generally reliable.
> Smaller local models via Ollama may ignore or misformat tool calls.

---

## Mixing Models in Multi-Agent Systems

One of the most powerful patterns enabled by this SPI is assigning **different models to
different agents** within the same multi-agent pipeline. Each `LlmAgent` sets its own
`model` field independently - ADK's orchestration layer does not care which LLM is underneath.

**Practical example: cost-optimised research pipeline**

```java
// Researcher - needs GoogleSearchTool, must be Gemini
LlmAgent researcher = LlmAgent.builder()
 .name("researcher")
 .model("gemini-2.5-flash")
 .tools(new GoogleSearchTool())
 .build()
 
// Writer - heavy reasoning, use a capable hosted model
LlmAgent writer = LlmAgent.builder()
 .name("writer")
 .model("groq/llama-3.1-8b-instant") // Groq free tier: fast, no cost
 .build()
 
// Critic - lightweight review pass, smallest model is fine
LlmAgent critic = LlmAgent.builder()
 .name("critic")
 .model("ollama/llama3") // Ollama: fully local, zero cost
 .build()
 
SequentialAgent pipeline = SequentialAgent.builder()
 .name("pipeline")
 .subAgents(List.of(researcher, writer, critic))
 .build()
```

**Why this matters:**
- Use **Gemini** only where its built-in tools (`GoogleSearchTool`, code execution)
are actually needed - keeping Gemini API usage minimal
- Route **heavy generation** tasks to fast free-tier hosted models (Groq, OpenRouter)
- Run **lightweight** classification, critique, or formatting steps on local Ollama models
at zero cost
- The result is a more powerful system at significantly lower cost than running every 
agent on a premium model
- Individual developers and open-source projects can build capable multi-agent Java
systems using only free-tier (Groq, OpenRouter) and local (Ollama) models - no
enterprise AI subscription required

> This pattern is demonstrated in `MultiAgentDemoApp` - swap individual agent models
> without changing any orchestration logic.

---

## `GoogleSearchTool` and Non-Google Models
`GoogleSearchTool` is Gemini-specific - it injects a `GoogleSearch` grounding config
into the request and only works with `gemini-2.*` or `gemini-3.*` models.
A non-Google model cannot use it directly.

### Bridge pattern: `GoogleSearchAgentTool`

ADK ships `GoogleSearchAgentTool`, a ready-made `AgentTool` that wraps a Gemini sub-agent
as a callable tool. A non-Google orchestrating agent simply invokes it like any other 
`FunctionTool` - the routing to Gemini happens transparently inside `AgentTool.runAsync()`.

```java
ModelProviderRegistry.registerAll();  // requires Groq / Ollama / OpenRouter

// Inner agent: Gemini 2.5 Flash + native Google Search grounding
GoogleSearchAgentTool searchTool = 
		GoogleSearchAgentTool.create(LlmRegistry.getLlm("gemini-2.5-flash"));
		
// Outer agent: any non-Google model - sees google_search_agent as a normal tool call
LlmAgent analyst = LlmAgent.builder()
	.name("research-analyst")
	.model("groq/llama-3.1-8b-instant")
	.instruction("When you need up-to-date information, call google_search_agent.")
	.tool(searchTool)
	.build();
```

Data flow:
```
User prompt
    |
Groq agent -> tool_call: google_search_agent({"request": "..."})
    |
AgentTool.runAsync() -> nested InMemoryRunner
    |
Gemini 2.5 Flash + GoogleSearchTool (live web grounding)
    |
result returned to Groq agent as a tool response
	|
Groq synthesises the final answer
```

**Prerequisites:** `GROQ_API_KEY` + `GEMINI_API_KEY` or (`GOOGLE_API_KEY`).
See `AgentToolDemoApp` for runnable example.

### Schema type normalization 

`AgentTool.declaration()` builds its `FunctionDeclaration` using Google genai's
`Schema` type, which uses uppercase enum names (`"OBJECT"`, `"STRING"`). The
OpenAI wire format requires lowercase JSON Schema types (`"object"`, `"string"`).

Both `OpenAiJsonSerializer` and proposed `AdkChatSerializer` need to apply a
`normalizeSchemaTypes(JsonNode)` helper that recursively lowercases every `"type"`
field before the declaration is sent to the provider. Without this normalization, Groq
(and most other OpenAI-compatible endpoints) return HTTP 400 with an "invalid JSON schema"
error for any agent that carries an `AgentTool`.

### Alternative: bring your own search API

If you don't have a Gemini key, any search HTTP API works as a plain `FunctionTool`:

```java 
public Map<String Object> webSearch(String query) {/*call any search API*/}
FunctionTool.create(this, "webSearch")
```

Recommended search APIs for LLM agents: **Tavily** (free tier, designed for agents),
**Brave Search API** (free tier), **Google Custom Search JSON API** (paid).
  
---

## Demo Classes
| Class | What it shows | Extra prereqs |
|---|---|---|
| `DemoApp` | SPI auto-discovery via `ModelProviderRegistry.registerAll()` - prints registered providers and runs a single turn | - |
| `SessionDemoApp` | Multi-turn conversation memory - same `InMemoryRunner` + sessionId reused across three turns | - |
| `StreamingDemoApp` | SSE token-by-token streaming - partial events printed inline as they arrive | - |
| `ToolsDemoApp` | Three `FunctionTool`s (`getCurrentTime`, `getWeather`, `calculate`) wired to a live agent | - |
| `AgentToolDemoApp` | `GoogleSearchAgentTool`: Groq outer agent delegates live search to a Gemini sub-agent | GEMINI_API_KEY | 
| `McpStdioDemoApp` | MCP filesystem tools via `@modelcontextprotocol/server-filesystem` (stdio) | Node.js + npx |
| `StructuredDemoApp` | `outputSchema` - agent extracts typed JSON (`title`, `director`, `year`, `genre`, `summary`) from a free-text movie blurb | - |
| `ParallelAgentDemoApp` | `ParallelAgent` - historian, scientiest, and economist run concurrently on one topic | - |
| `MultiAgentDemoApp` | `SequentialAgent` + `LoopAgent` : researcher -> (writer <-> critic x2); shows `outputKey` + `Instruction.Provider` for inter-agent state passing | GEMINI_API_KEY |
| `CallbacksDemoApp` | `beforeModel`, `afterModel`, `beforeTool`, `afterTool` lifecycle callbacks; includes a guardrail that short-circuits the tool call | - |
| `WebServerDemoApp` | ADK Dev web server (`AdkWebServer`) - chat via browser at `http://localhost:8080` | - |

---

## Prerequisites

```powershell
# OpenRouter (used by all demos)
$env:OPENROUTER_API_KEY = "your_key_here"

# Gemini (used in AgentToolDemoApp and MultiAgentDemoApp to utilize GoogleSearchTool)
$env:GEMINI_API_KEY = "your_key_here"

# Groq (optional - swap model names in the demo classes to use it)
$env:GROQ_API_KEY = "your_key_here"

# Ollama (optional - no key needed, just have it running)
ollama server
```

## Running
All commands run from the **repo root**. On Powershell, quote the `-D` value.

```powershell
# SPI registration demo (prints registered providers, no interface call)
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.DemoApp"

# SessionDemo - multi-turn memory: three turns share one sessionId
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.SessionDemoApp"

# Streaming demo - partial SSE tokens printed inline as they arrive
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.StreamingDemoApp"

# Tool-calling demo - agent calls getCurrentTime, getWeather, and calculate
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.tools.ToolsDemoApp"

# AgentTool-calling demo - Groq -> GoogleSearchAgentTool -> Gemini (requires GEMINI_API_KEY)
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.agenttool.AgentToolDemoApp"

# MCP demo via npx/filesystem (requires Node.js)
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.mcp.McpStdioDemoApp"

# Structured output demo - typed JSON extracted from a movie blurb
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.structured.StructuredOutputDemoApp"

# Parallel Agent Demo - historian, scientist, economist run concurrently
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.parallel.ParallelDemoApp"

# Multi-agent pipeline demo (SequentialAgent: researcher -> (writer <-> critic x2)
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.multiagent.MultiAgentDemoApp"

# Callbacks demo - beforeModel, afterModel, beforeTool, afterTool; guardrail example
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.callbacks.CallbacksDemoApp"

# ADK Dev Web server - open http://localhost:8080 in the ADK Dev UI
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.WebServerDemoApp"

# Web server demo on a custom port
mvn -pl model-prism-demo exec:java "-Dexec.mainClass=com.google.adk.models.demo.WebServerDemoApp" "-Dexec.args=--server.port=9090"
```

On bash:

```bash
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.DemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.SessionDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.StreamingDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.tools.ToolsDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.agenttool.AgentToolDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.mcp.McpStdioDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.structured.StructuredOutputDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.parallel.ParallelAgentDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.multiagent.MultiAgentDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.callbacks.CallbacksDemoApp
mvn -pl model-prism-demo exec:java -Dexec.mainClass=com.google.adk.models.demo.WebServerDemoApp
```

---

## How the ServiceLoader Mechanism Works

```
META-INF/services/com.google.adk.models.spi.ModelProvider
	|   (one file per provider JAR, one entry per file)
	|-- adk-model-prism-groq.jar       -> com.google.adk.models.groq.GroqModelProvider
	|-- adk-model-prism-ollama.jar     -> com.google.adk.models.ollama.OllamaModelProvider
	|-- adk-model-prism-openrouter.jar -> com.google.adk.models.openrouter.OpenRouterModelProvider
```

At runtime:

```java
ServiceLoader<ModelProvider> loader = ServiceLoader.load(ModelProvider.class);
for(ModelProvider provider : loader) {
	LlmRegistry.registerLlm(provider.modelPattern(), provider::create);
}
```

Each provider JAR is fully self-contained and independently deployable.

---

## Proposing This Upstream

Three files from `adk-model-prism-core` would go into `google-adk.jar`
- `ModelProvider.java` - public SPI interface
- `ModelProviderRegistry.java` - ServiceLoader wiring
- `OpenAiCompatibleLlm.java` - reusable base class for Open-AI-format APIs

First integration point: `Runner`

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

Provider JARs are published independently - no PRs to ADK core needed for new providers.

---

## Optional: `ADKChatSerializer` - Richer responses via ADK 1.1.0 Internals

The default serializer (`DefaultOpenAiMessageSerializer`) maps the essential fields
of the OpenAI response - text content and tool calls. ADK 1.1.0 ships internal DTOs in 
`com.google.adk.models.chat` that already handle a richer mapping. 
Implement `AdkChatSerializer` to utilize those DTOs to additionally populate usage metadata,
model version, custom metadata, etc...

**How to wire it in**
 
Pass `AdkChatSerializer` via the injection constructor when creating a provider:

```java 
// In your ModelProvider.create() implementation:
BaseLlm create(String modelName){
	String model = modelName.substring("groq/".length());
	return new OpenAiCompatibleLlm(
		model,
		new DefaultOpenAiHttpClient("https://api.groq.com/openai/v1/chat/completions"),
					System.getenv("GROQ_API_KEY")),
		new AdkChatSerializer()); // <- swap in here
}
```

Or subclass `OpenAiCompatibleLlm` directly:

```java
public class GroqLlm extends OpenAiCompatibleLlm {
	public GroqLlm(String modelName, String apiKey){
		super(modelName, 
		new DefaultOpenAiHttpClient("https://api.groq.com/openai/v1/chat/completions", apiKey),
		new AdkChatSerializer());
	}
}
```



