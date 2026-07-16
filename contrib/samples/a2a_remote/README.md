# A2A Remote Prime Service Sample

This sample starts a standalone Spring Boot service that exposes the
`remote_prime_agent` via the shared A2A webservice module
(`google-adk-a2a-webservice`). It behaves like a third‑party service that
implements the A2A JSON‑RPC contract and can be used by the ADK client (for
example, the `a2a_basic` demo) as its remote endpoint.

## Running the service

```bash
cd google_adk
mvn -f contrib/samples/a2a_remote/pom.xml package

GOOGLE_GENAI_USE_VERTEXAI=FALSE \
GOOGLE_API_KEY=<your_gemini_api_key> \
mvn -f contrib/samples/a2a_remote/pom.xml exec:java
```

`RemoteA2AApplication` imports the reusable controller/service from
`google-adk-a2a-webservice`, so the server listens on
`http://localhost:8080/a2a/remote/v1/message:send` by default. Override the
port with `-Dspring-boot.run.arguments=--server.port=<port>` when running via
`spring-boot:run` if you need to avoid collisions.

```
POST /a2a/remote/v1/message:send
Content-Type: application/json
```

and accepts standard A2A JSON‑RPC payloads (`SendMessageRequest`). The
response is a `SendMessageResponse` that contains either a `Message` or a
`Task` in the `result` field. Spring Boot logs the request/response lifecycle
to the console; add your preferred logging configuration if you need
persistent logs.

## Agent implementation

- `remote_prime_agent/Agent.java` hosts the LLM agent that checks whether
  numbers are prime (lifted from the Stubby demo). The model name defaults
  to `gemini-2.5-pro`; set `GOOGLE_API_KEY` before running.
- `RemoteA2AApplication` bootstraps the service by importing
  `A2ARemoteConfiguration` and publishing the prime `BaseAgent` bean. The shared
  configuration consumes that bean to create the `A2ASendMessageExecutor`.

## Sample request

```bash
curl -X POST http://localhost:8080/a2a/remote/v1/message:send \
  -H 'Content-Type: application/json' \
  -d '{
        "jsonrpc": "2.0",
        "id": "demo-123",
        "method": "message/send",
        "params": {
          "message": {
            "role": "user",
            "messageId": "msg-1",
            "contextId": "ctx-1",
            "parts": [
              {"kind": "text", "text": "Check if 17 is prime"}
            ]
          },
          "metadata": {}
        }
      }'
```

The response contains the prime check result, and the interaction is logged in
the application console.
