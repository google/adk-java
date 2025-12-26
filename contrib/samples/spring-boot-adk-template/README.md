# Spring Boot ADK Template

This project is a Spring Boot application that serves as a template and verification app for the **Google ADK Spring Boot Starter**.

It demonstrates how to integrate the Google Agent Development Kit (ADK) into a Spring Boot application using the custom starter.

## Features

*   **ADK Integration**: Uses `adk-spring-boot-starter` for auto-configuration.
*   **Agent Configuration**: Defines a sample `LlmAgent` bean.
*   **Bean Verification**: Verifies the injection of core ADK components (`Runner`, `RunConfig`, `ArtifactService`, etc.) on startup.
*   **Reactive Web**: Built on Spring WebFlux (though the current test scenario is console-based).

## Prerequisites

*   Java 21
*   Maven
*   **adk-spring-boot-starter**: This project depends on the local `adk-spring-boot-starter` artifact. You must build and install the starter first.

## Getting Started

### 1. Build the ADK Starter

Before running this application, ensure the starter is installed in your local Maven repository:

```bash
cd ../adk-spring-boot-starter
./mvnw clean install -DskipTests
```

### 2. Build and Run the Template

```bash
cd ../SpringBootAdkTemplate
./mvnw spring-boot:run
```

### 3. Verify Output

On successful startup, you should see logs indicating the ADK components have been initialized:

```text
=== ADK Starter Verification ===
Service Info: Agent created: LlmAgent, Runner: ...
Run Config streaming mode: NONE
RunConfig: com.google.adk.agents.RunConfig
Runner: com.google.adk.runner.Runner
BaseArtifactService: com.google.adk.artifacts.BaseArtifactService
...
================================
```

## Configuration

The application is configured via `src/main/resources/application.yaml`.

Key ADK properties (managed by the starter):

```yaml
adk:
  run-config:
    streaming-mode: NONE # Options: NONE, SSE, BIDI
  artifacts:
    gcs-enabled: false # Set to true to use Google Cloud Storage
    # bucket-name: my-bucket
  session:
    type: IN_MEMORY # Options: IN_MEMORY, VERTEX_AI
  memory:
    type: IN_MEMORY
```

## Project Structure

*   `src/main/java/com/example/SpringBootAdkTemplate/config/AgentConfig.java`: Defines the `LlmAgent` and `Runner` beans.
*   `src/main/java/com/example/SpringBootAdkTemplate/AgentService.java`: Service that uses the `Runner`.
*   `src/main/java/com/example/SpringBootAdkTemplate/SpringBootAdkTemplateApplication.java`: Main entry point with verification logic.
