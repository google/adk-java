# Google ADK A2A Agent Server Sample

This sample demonstrates how to expose a Google ADK (Agent Development Kit)
agent via the A2A (Agent-to-Agent) protocol using A2A SDK and Quarkus service.

## Overview

The application implements a simple conversational agent that checks whether
given numbers are prime numbers. It uses the `LlmAgent` from the Google ADK and
exposes it via an A2A server.

### Key Components

*   **`Agent.java`**: Defines the `LlmAgent` instance (`check_prime_agent`) and
    the `checkPrime` tool function it uses to verify numbers.
*   **`AgentCardProducer.java`**: Loads and provides the `AgentCard` metadata
    (from `agent.json`) which defines the agent's identity and capabilities in
    the A2A network.
*   **`AgentExecutorProducer.java`**: Configures and provides the A2A
    `AgentExecutor`, implemented by the ADK library to wire ADK-owned agents
    automatically.
*   **`StartupConfig.java`**: Contains initialization logic, such as registering
    JSON modules for the Vert.x/Quarkus runtime.
*   **`application.properties`**: Contains a configuration for the Quarkus
    service and A2A, such as port where application will be exposed, application
    name and event processing timeouts.

## Building the Project

You can build the project using Maven:

```shell
mvn clean install
```

The Java server can be started using `mvn` as follow (don't forget to set your
GOOGLE_API_KEY before running the service):

```bash
export GOOGLE_API_KEY=<YOUR_API_KEY>

cd contrib/samples/a2a_server
mvn quarkus:dev
```

## Sample request

```bash
curl -X POST http://localhost:9090 \
  -H 'Content-Type: application/json' \
  -d '{
        "jsonrpc": "2.0",
        "id": "cli-check-2",
        "method": "message/stream",
        "params": {
          "message": {
            "kind": "message",
            "contextId": "cli-demo-context",
            "messageId": "cli-check-2",
            "role": "user",
            "parts": [
              {
                "kind": "text",
                "text": "Is 2 prime?"
              }
            ]
          }
        }
      }'
```
