# Spring Chat Advanced Sample

A Spring Boot order-support chat backed by an ADK agent that calls its model
through Spring AI, using the ADK Spring AI integration (`google-adk-spring-ai`).
For the minimal version, see [`spring_chat`](../spring_chat/README.md).

## How it fits together

-   `OrderService` is a plain Spring bean. `AgentConfig` turns two of its
    methods into ADK `FunctionTool`s, so tools can use anything Spring injects.
    `FunctionTool` reads the `@Schema` annotations from the bean's class, so if
    you add aspects such as `@Transactional`, which make Spring proxy the bean,
    pass the `Method` from `OrderService.class` instead of a method name.
-   `ShippingTools` holds Spring AI `@Tool` methods. `AgentConfig` wraps them in
    a `SpringAiToolset`, so the agent uses them next to the ADK tools.
    `getTracking` records the tracked order in the ADK session state, which the
    agent's instruction reads. `talkToHuman` uses `returnDirect`, so its result
    goes to the user without another model call. Tools the model calls in the
    same turn still run, but get no model reply.
-   `ChatController` runs the agent in `StreamingMode.SSE` and forwards its
    events to the browser as server-sent events: reply chunks as they arrive,
    the complete reply, each tool call and result, and why the model stopped if
    it ended early.
-   `static/index.html` reads the stream with `fetch` and shows the reply as it
    grows.
-   When the browser has disconnected, the controller notices on its next send
    and cancels the agent run, which cancels the model call.

Each page load gets its own ADK session, so the agent remembers the conversation
until you reload. Sessions are kept in memory until the app stops.

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
./mvnw install -DskipTests -pl examples/spring_chat_advanced -am
./mvnw spring-boot:run -pl examples/spring_chat_advanced
```

Then open http://localhost:8080 and ask, for example, "When do orders 1001 and
1002 arrive?". The chat endpoint has no authentication, so the app listens on
`localhost` only, which keeps other machines out. Set `SERVER_ADDRESS=0.0.0.0`
to accept connections from other hosts. Don't expose it beyond a trusted
environment without adding authentication.
