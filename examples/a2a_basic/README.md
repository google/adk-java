# A2A Basic Sample

This sample shows how to invoke an A2A-compliant HTTP endpoint from the Google
ADK runtime using the reusable `google-adk-a2a` module. It wires a
`RemoteA2AAgent` to the production `JdkA2AHttpClient`, so you can exercise a
running service (for example the `a2a_server` sample).

## Prerequisites

1.  Start the `a2a_server` sample (or point to any other A2A-compliant
    endpoint):

    ```bash
    cd google_adk
    GOOGLE_API_KEY=your_api_key \
      ./mvnw -f examples/a2a_server/pom.xml quarkus:dev
    ```

## Build and run

```bash
cd google_adk
./mvnw -f examples/a2a_basic/pom.xml exec:java \
  -Dexec.args="http://localhost:9090"
```

You should see the client log each turn, including the remote agent response
(e.g. `4 is not a prime number.`).

To run the client in the background and capture logs:

```bash
nohup env GOOGLE_GENAI_USE_VERTEXAI=FALSE \
  GOOGLE_API_KEY=your_api_key \
  ./mvnw -f examples/a2a_basic/pom.xml exec:java \
  -Dexec.args="http://localhost:9090" \
  > /tmp/a2a_basic.log 2>&1 & echo $!
```

Tail `/tmp/a2a_basic.log` to inspect the conversation.

## Key files

-   `A2AAgent.java` – builds a root agent with a local dice-rolling tool and a
    remote prime-checking sub-agent.
-   `A2AAgentRun.java` – minimal driver that executes a single `SendMessage`
    turn to demonstrate the remote call.
-   `pom.xml` – standalone Maven configuration for building and running the
    sample.
