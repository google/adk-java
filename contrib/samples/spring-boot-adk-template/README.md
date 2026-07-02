# Spring Boot ADK Template

Sample Spring Boot application demonstrating the **`google-adk-spring-boot-starter`** in its simplest form. The user code consists of:

- One `@Bean LlmAgent rootAgent()` — the agent topology
- One `@Bean App` — wraps the root agent under an app name
- One `@Service AgentService` — illustrates injection of the starter-provided `Runner`

Everything else (`Runner`, `BaseSessionService`, `BaseArtifactService`, `RunConfig`) is wired by the starter.

## Prerequisites

- Java 17
- Maven
- This module is built as part of the ADK aggregator; no separate install of the starter is required.

## Build and run

From the repository root:

```bash
mvn -pl contrib/samples/spring-boot-adk-template -am verify
```

To run interactively:

```bash
mvn -pl contrib/samples/spring-boot-adk-template -am spring-boot:run
```

## Configuration

`src/main/resources/application.yaml` shows the full property surface as comments. The defaults give an all-in-memory configuration with no GCP credentials required — switch backends by uncommenting the relevant blocks (`adk.session.type=VERTEX_AI`, `adk.session.type=FIRESTORE`, `adk.artifacts.gcs-enabled=true`, etc.).

See the [starter README](../../spring-boot-starter/README.md) for the full property reference.

## Use Spring AI as the LLM substrate (optional)

This sample uses a string model name (`"gemini-2.5-flash"`). To swap to Spring AI's `ChatModel` ecosystem (OpenAI, Anthropic, Gemini, Ollama, Vertex AI, Azure OpenAI, Bedrock):

1. Add `com.google.adk:google-adk-spring-ai` and your preferred Spring AI provider artifact to `pom.xml`.
2. Configure the provider via `spring.ai.*` properties (e.g. `spring.ai.openai.api-key`).
3. Inject the auto-configured `SpringAI` bean into `AgentConfig.rootAgent()` and pass it to `.model(...)`.

## Project structure

```
src/main/java/com/example/springbootadktemplate/
├── SpringBootAdkTemplateApplication.java   — @SpringBootApplication entry point
├── AgentService.java                        — sample @Service injecting Runner + LlmAgent
└── config/AgentConfig.java                  — @Bean LlmAgent + @Bean App
src/main/resources/application.yaml          — spring.application.name + (commented) adk.* properties
src/test/java/com/example/springbootadktemplate/
└── SpringBootAdkTemplateApplicationTest.java — context-load test asserting every starter bean is reachable
```
