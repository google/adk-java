# A2A Basic Sample

This sample shows how to invoke an A2A-compliant HTTP endpoint from the Google
ADK runtime using the reusable `google-adk-a2a` module. It wires a
`RemoteA2AAgent` to the production `JdkA2AHttpClient`, so you can exercise a
running service (for example the Spring Boot webservice in
`a2a/webservice`).

## Prerequisites

1. Start the Spring service (or point to any other A2A-compliant endpoint):

   ```bash
   cd /google_adk
   ./mvnw -f a2a/webservice/pom.xml spring-boot:run \
     -Dspring-boot.run.arguments=--server.port=8081
   ```

## Build and run

```bash
cd google_adk
./mvnw -f contrib/samples/a2a_basic/pom.xml exec:java \
  -Dexec.args="http://localhost:8081/a2a/remote"
```

You should see the client log each turn, including the remote agent response
(e.g. `4 is not a prime number.`).

To run the client in the background and capture logs:

```bash
nohup env GOOGLE_GENAI_USE_VERTEXAI=FALSE \
  GOOGLE_API_KEY=your_api_key \
  ./mvnw -f contrib/samples/a2a_basic/pom.xml exec:java \
  -Dexec.args="http://localhost:8081/a2a/remote" \
  > /tmp/a2a_basic.log 2>&1 & echo $!
```

Tail `/tmp/a2a_basic.log` to inspect the conversation.

## Key files

- `A2AAgent.java` – builds a root agent with a local dice-rolling tool and a
  remote prime-checking sub-agent.
- `A2AAgentRun.java` – minimal driver that executes a single
  `SendMessage` turn to demonstrate the remote call.
- `pom.xml` – standalone Maven configuration for building and running the
  sample.
