# Context Caching Sample

This sample shows explicit context caching with the Google ADK. A weather
assistant answers several questions in one session. Its large system instruction
is shared by every turn, so the app caches it on the Gemini backend with
`App.contextCacheConfig`, and later turns reference the cached instruction
instead of resending it.

The runner prints what the cache does on each turn:

-   Turn 1 has no cache yet. It only records a fingerprint of the cacheable
    prefix.
-   Once the prefix is known to be stable, a cache is CREATED. Later turns REUSE
    it, and `invocationsUsed` grows.
-   After the third turn the runner waits for the cache's one-minute TTL, the
    shortest Vertex AI accepts, to run out. The next turn RE-CREATES the cache,
    usually with more cached contents.
-   Each turn also prints its prompt token count and how many of those tokens
    the cache served.

Exact turn-by-turn output depends on model latency. A slow turn can outlast the
one-minute TTL, so a later turn may print an extra RE-CREATED.

Caching only starts once the cached prefix reaches the model's minimum size:
2048 tokens for Gemini 2.5 and 4096 tokens for Gemini 3.

## Project Layout

```
├── ContextCachingAgent.java  // Agent, app and caching configuration
├── ContextCachingRun.java    // Console runner entry point
├── pom.xml                   // Maven configuration and exec main class
└── README.md                 // This file
```

## Prerequisites

-   Java 17+
-   Maven 3.9+
-   Credentials for the Gemini API (`GOOGLE_API_KEY`) or Vertex AI
    (`GOOGLE_GENAI_USE_VERTEXAI=true`, `GOOGLE_CLOUD_PROJECT` and
    `GOOGLE_CLOUD_LOCATION`)

## Build and Run

```bash
mvn clean compile exec:java
```

The sample uses `gemini-3.8-flash`. To try another model, pass its name:

```bash
mvn compile exec:java -Dexec.args="<model name>"
```
