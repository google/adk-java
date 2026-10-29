# Spring Chat Sample

A minimal Spring Boot web app with a browser chat window backed by an ADK agent.
The agent calls its model through Spring AI, using the ADK Spring AI integration
(`google-adk-spring-ai`).

## How it fits together

-   `spring-ai-starter-model-google-genai` creates a Spring AI `ChatModel`.
-   The ADK Spring AI auto-configuration wraps it in a `SpringAI` bean, which is
    an ADK `BaseLlm`.
-   `AgentConfig` builds an `LlmAgent` on that model and an `InMemoryRunner`.
-   `ChatController` exposes `POST /api/chat`, and `static/index.html` is the
    chat page. Each page load gets its own ADK session, so the agent remembers
    the conversation until you reload. Sessions are kept in memory until the app
    stops.

To use another provider, replace the Google GenAI starter in `pom.xml` with any
other Spring AI chat model starter (OpenAI, Anthropic, Ollama, ...) and set its
`spring.ai.*` properties.

## Prerequisites

-   Java 17+
-   A Google Cloud project with Vertex AI enabled, and Application Default
    Credentials (`gcloud auth application-default login`).

The app uses Vertex AI by default. Set `GOOGLE_CLOUD_PROJECT`, and optionally
`GOOGLE_CLOUD_LOCATION` (default `global`) and `GEMINI_MODEL`. To use the Gemini
Developer API instead, set `GOOGLE_GENAI_USE_VERTEXAI=false` and
`GOOGLE_API_KEY`.

## Run

From the repository root:

```bash
export GOOGLE_CLOUD_PROJECT=your-project-id
./mvnw install -DskipTests -pl examples/spring_chat -am
./mvnw spring-boot:run -pl examples/spring_chat
```

Then open http://localhost:8080. The chat endpoint has no authentication, so the
app listens on `localhost` only, which keeps other machines out. Set
`SERVER_ADDRESS=0.0.0.0` to accept connections from other hosts. Don't expose it
beyond a trusted environment without adding authentication.
