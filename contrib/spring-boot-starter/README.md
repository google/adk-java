# ADK Spring Boot Starter

Spring Boot auto-configuration for the [Agent Development Kit (ADK)](https://github.com/google/adk-java) runtime.

After adding this starter, declare a single `@Bean App` (your agent topology) and the starter wires the rest: `Runner`, `BaseSessionService`, `BaseArtifactService`, optionally `BaseMemoryService`, and `RunConfig`. The starter is **LLM-agnostic** — pair it with `google-adk-spring-ai` for Spring AI LLMs, or declare your own `@Bean BaseLlm`.

## Installation

```xml
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-spring-boot-starter</artifactId>
    <version>${adk.version}</version>
</dependency>

<!-- Recommended companion for Spring AI users (OpenAI / Anthropic / Gemini / Ollama / Azure OpenAI / Bedrock): -->
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-spring-ai</artifactId>
    <version>${adk.version}</version>
</dependency>
```

The starter declares `google-adk-firestore-session-service` as an `<optional>true</optional>` dependency. It is on your runtime classpath by default — explicitly exclude it if you do not want Firestore-related auto-configurations to activate.

## Quick Start

```java
@SpringBootApplication
class MyApp {
  public static void main(String[] args) { SpringApplication.run(MyApp.class, args); }
}

@Configuration
class MyAgents {
  @Bean public LlmAgent rootAgent(SpringAI llm) {       // SpringAI bean comes from google-adk-spring-ai
    return LlmAgent.builder()
        .name("root_agent")
        .model(llm)
        .instruction("Answer concisely.")
        .build();
  }
  @Bean public App app(LlmAgent rootAgent,
                       @Value("${spring.application.name}") String appName) {
    return App.builder().name(appName).rootAgent(rootAgent).build();
  }
}

@RestController
class ChatController {
  private final Runner runner;                          // provided by starter
  private final RunConfig runConfig;                    // provided by starter
  private final BaseSessionService sessionService;      // provided by starter
  ChatController(Runner r, RunConfig c, BaseSessionService s) {
    this.runner = r; this.runConfig = c; this.sessionService = s;
  }
  @PostMapping("/chat") String chat(@RequestBody String prompt) {
    String userId = "alice";
    String sessionId = UUID.randomUUID().toString();
    sessionService.createSession(runner.appName(), userId, null, sessionId).blockingGet();
    Content msg = Content.builder().role("user").parts(List.of(Part.builder().text(prompt).build())).build();
    return runner.runAsync(userId, sessionId, msg, runConfig)
        .toList().blockingGet()
        .stream().map(Event::stringifyContent).collect(joining());
  }
}
```

## Property Reference

```yaml
spring:
  application:
    name: my_app                       # used as the App's appName (must match validateAppName regex)

adk:
  artifacts:
    gcs-enabled: false                 # default; switch to true to use Google Cloud Storage
    # bucket-name: my-artifacts-bucket # required when gcs-enabled=true (fail-fast otherwise)

  session:
    type: IN_MEMORY                    # IN_MEMORY | VERTEX_AI | FIRESTORE
    # project-id: my-gcp-project       # required for VERTEX_AI
    # location: us-central1            # required for VERTEX_AI

  memory:
    type: IN_MEMORY                    # IN_MEMORY | FIRESTORE
                                       # VERTEX_AI is reserved — fails fast (no impl exists in ADK)

  run-config:
    streaming-mode: NONE               # NONE | SSE | BIDI
    max-llm-calls: 500
    tool-execution-mode: NONE          # NONE | SEQUENTIAL | PARALLEL | PARALLEL_SUBSCRIBE
    save-input-blobs-as-artifacts: false
    auto-create-session: false

  firestore:                           # only consulted when session/memory type=FIRESTORE
    # project-id: my-gcp-project       # optional — falls back to ADC project
    # database-id: "(default)"         # optional — falls back to "(default)"
```

## Persistence Backends

| Concern    | `IN_MEMORY` | `VERTEX_AI`                        | `FIRESTORE`                                | GCS (artifacts only) |
|------------|-------------|------------------------------------|--------------------------------------------|----------------------|
| Sessions   | default     | `VertexAiSessionService` (managed) | `FirestoreSessionService` (contrib module) | —                    |
| Memory     | default     | n/a — fails fast                   | `FirestoreMemoryService` (contrib module)  | —                    |
| Artifacts  | default     | n/a                                | n/a                                        | `GcsArtifactService` |

The Firestore branches activate only when the `google-adk-firestore-session-service` module is on the classpath (gated by `@ConditionalOnClass`). The starter declares it as an optional dependency — exclude it from your application pom if you want to keep Firestore wiring off the classpath entirely.

## Multiple business-unit agents in one Spring Boot app

ADK's `appName` is the partition key for sessions, memory, and artifacts. A single Spring Boot app can host multiple independent agentic applications (one per business unit) by declaring multiple `@Bean App`. The starter's `@Bean Runner` auto-config uses `@ConditionalOnSingleCandidate(App.class)`, so it steps aside cleanly when multiple Apps exist — wire one `Runner` per `App` explicitly:

```java
@Configuration
class BusinessUnits {

  @Bean App salesApp(SpringAI llm) {
    return App.builder().name("sales").rootAgent(salesRootAgent(llm)).build();
  }
  @Bean App supportApp(SpringAI llm) {
    return App.builder().name("support").rootAgent(supportRootAgent(llm)).build();
  }
  // ... per-BU rootAgent bean factories ...

  @Bean Runner salesRunner(
      @Qualifier("salesApp") App app,
      BaseArtifactService artifactService,
      BaseSessionService sessionService) {
    return Runner.builder().app(app).artifactService(artifactService).sessionService(sessionService).build();
  }
  @Bean Runner supportRunner(
      @Qualifier("supportApp") App app,
      BaseArtifactService artifactService,
      BaseSessionService sessionService) {
    return Runner.builder().app(app).artifactService(artifactService).sessionService(sessionService).build();
  }
}
```

The shared `BaseSessionService`, `BaseArtifactService`, `BaseMemoryService`, and `RunConfig` beans are singletons partitioned internally by `appName`. No duplication — and no per-BU service wiring boilerplate.

## Architecture

Eight auto-configuration classes, each owning one concern:

| Auto-config                                    | Produces                              | Activation                                                                    |
|------------------------------------------------|---------------------------------------|-------------------------------------------------------------------------------|
| `AdkArtifactsAutoConfiguration`                | `BaseArtifactService`, conditional `Storage` | always; `Storage` only when `adk.artifacts.gcs-enabled=true`                 |
| `AdkSessionAutoConfiguration`                  | `BaseSessionService` (IN_MEMORY, VERTEX_AI) | always; FIRESTORE falls through to the Firestore variant when on classpath  |
| `AdkFirestoreSessionAutoConfiguration`         | `BaseSessionService` (FIRESTORE)      | `@ConditionalOnClass(FirestoreSessionService.class)`; `@AutoConfigureBefore`  |
| `AdkMemoryAutoConfiguration`                   | `BaseMemoryService` (IN_MEMORY)       | always; FIRESTORE falls through; VERTEX_AI fails fast                         |
| `AdkFirestoreMemoryAutoConfiguration`          | `BaseMemoryService` (FIRESTORE)       | `@ConditionalOnClass(FirestoreMemoryService.class)`; `@AutoConfigureBefore`   |
| `AdkRunConfigAutoConfiguration`                | `RunConfig`                           | always                                                                        |
| `AdkFirestoreAutoConfiguration`                | `Firestore` client                    | `@ConditionalOnClass(Firestore.class)`                                       |
| `AdkRunnerAutoConfiguration`                   | `Runner`                              | `@ConditionalOnBean(App.class)` + `@ConditionalOnSingleCandidate(App.class)` |

Every `@Bean` factory uses `@ConditionalOnMissingBean` so any user-declared override (`Storage`, `Firestore`, `BaseSessionService`, `Runner`, etc.) always wins.

## Failure Modes

The starter fails fast at startup (throwing `BeanCreationException` with a clear remediation message) when:

- `adk.artifacts.gcs-enabled=true` but `adk.artifacts.bucket-name` is blank.
- `adk.session.type=VERTEX_AI` but `project-id` or `location` is blank.
- `adk.session.type=FIRESTORE` but `google-adk-firestore-session-service` is not on the classpath.
- `adk.memory.type=VERTEX_AI` (no Vertex AI memory service exists in ADK today).
- `adk.memory.type=FIRESTORE` but the contrib jar is missing.

## Roadmap — not in this module

Bridges from Spring AI primitives into ADK service interfaces (`ChatMemory` → sessions, `VectorStore` → memory, `ToolCallback` → tools incl. MCP) are planned for `contrib/spring-ai` as a follow-up PR. Once they land, this starter will gain `SPRING_AI_CHAT_MEMORY` / `SPRING_AI_VECTOR_STORE` enum values that activate the bridges when the contrib classes are on the classpath. New artifact backends (S3, Azure Blob, filesystem) require new `BaseArtifactService` impls upstream in ADK first.
