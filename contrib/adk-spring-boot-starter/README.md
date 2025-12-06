# ADK Spring Boot Starter

This starter integrates the Google Agent Development Kit (ADK) into Spring Boot applications. 
It provides auto-configuration for Runners, and core services like Artifacts, Session, and Memory management.

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>adk-spring-boot-starter</artifactId>
    <version>${adk-starter.version}</version>
</dependency>
```

## Configuration

By default, the starter configures all services to use **In-Memory** implementations. This is great for local development and testing.

You can configure the behavior using `application.yaml`:

### 1. Artifacts Service

*   **Default**: `InMemoryArtifactService`
*   **Production**: `GcsArtifactService` (Google Cloud Storage)

To enable GCS:

```yaml
adk:
  artifacts:
    gcs-enabled: true
    bucket-name: "my-agent-artifacts-bucket"
```

### 2. Session Service

*   **Default**: `InMemorySessionService`
*   **Production**: `VertexAiSessionService`

To enable Vertex AI Sessions:

```yaml
adk:
  session:
    type: VERTEX_AI
    project-id: "my-gcp-project"
    location: "us-central1"
```

### 3. Memory Service

*   **Default**: `InMemoryMemoryService`

Currently, only In-Memory memory service is supported by default.


### 4. Run Configuration

*   **Default Streaming Mode**: `NONE`

To enable streaming (e.g., SSE or BIDI):

```yaml
adk:
  run-config:
    streaming-mode: SSE # Options: NONE, SSE, BIDI
```

*Note: Other `RunConfig` options (like modalities, audio config) will be added in future versions.*

## Architecture

The starter follows the Single Responsibility Principle by splitting configuration into:
*   `AdkArtifactsAutoConfiguration`
*   `AdkSessionAutoConfiguration`
*   `AdkMemoryAutoConfiguration`
*   `AdkRunConfigAutoConfiguration`

Each has its own properties class (e.g., `AdkArtifactProperties`).

## Usage

Simply inject the beans into your application:

```java
@Service
public class MyAgentService {

    private final Runner runner;
    // Agent bean is no longer auto-configured by default.
    // You must define your own Agent bean or use the Runner directly.

    public MyAgentService(Runner runner) {
        this.runner = runner;
    }

    // ... use runner
}
```
