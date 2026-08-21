# ADK Telemetry and Tracing

This package contains classes for capturing and reporting telemetry data within
the ADK, primarily for tracing agent execution leveraging OpenTelemetry.

## Overview

The `Tracing` utility class provides methods to trace various aspects of an
agent's execution, including:

*   Agent invocations
*   LLM requests and responses
*   Tool calls and responses

These traces can be exported and visualized in telemetry backends like Google
Cloud Trace or Zipkin, or viewed through the ADK Dev Server UI, providing
observability into agent behavior.

## How Tracing is Used

Tracing is deeply integrated into the ADK's RxJava-based asynchronous workflows.

### Agent Invocations

Every agent's `runAsync` or `runLive` execution is wrapped in a span named
`invoke_agent <agent_name>`. The top-level agent invocation initiated by
`Runner.runAsync` or `Runner.runLive` is captured in a span named `invocation`.
Agent-specific metadata like name and description are added as span attributes,
following OpenTelemetry semantic conventions (e.g., `gen_ai.agent.name`).

### LLM Calls

Calls to Large Language Models (LLMs) are traced within a `call_llm` span. The
`traceCallLlm` method attaches detailed attributes to this span, including:

*   The LLM request (excluding large data like images) and response.
*   Model name (`gen_ai.request.model`).
*   Token usage (`gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`).
*   Configuration parameters (`gen_ai.request.top_p`,
    `gen_ai.request.max_tokens`).
*   Response finish reason (`gen_ai.response.finish_reasons`).

### Tool Calls and Responses

Tool executions triggered by the LLM are traced using `tool_call [<tool_name>]`
and `tool_response [<tool_name>]` spans.

*   `traceToolCall` records tool arguments in the
    `gcp.vertex.agent.tool_call_args` attribute.
*   `traceToolResponse` records tool output in the
    `gcp.vertex.agent.tool_response` attribute.
*   If multiple tools are called in parallel, a single `tool_response` span may
    be created for the merged result.

### Context Propagation

ADK is built on RxJava and heavily uses asynchronous processing, which means
that work is often handed off between different threads. For tracing to work
correctly in such an environment, it's crucial that the active span's context
is propagated across these thread boundaries. If context is not propagated,
new spans may be orphaned or attached to the wrong parent, making traces
difficult to interpret.

OpenTelemetry stores the currently active span in a thread-local variable.
When an asynchronous operation switches threads, this thread-local context is
lost. To solve this, ADK's `Tracing` class provides functionality to capture
the context on one thread and restore it on another when an asynchronous
operation resumes. This ensures that spans created on different threads are
correctly parented under the same trace.

The primary mechanism for this is the `Tracing.withContext(context)` method,
which returns an RxJava transformer. When applied to an RxJava stream via
`.compose()`, this transformer ensures that the provided `Context` (containing
the parent span) is re-activated before any `onNext`, `onError`, `onComplete`,
or `onSuccess` signals are propagated downstream. It achieves this by wrapping
the downstream observer with a `TracingObserver`, which uses
`context.makeCurrent()` in a try-with-resources block around each callback,
guaranteeing that the correct span is active when downstream operators execute,
regardless of the thread.

### RxJava Integration

ADK integrates OpenTelemetry with RxJava streams to simplify span creation and
ensure context propagation:

*   **Span Creation**: The `Tracing.trace(spanName)` method returns an RxJava
    transformer that can be applied to a `Flowable`, `Single`, `Maybe`, or
    `Completable` using `.compose()`. This transformer wraps the stream's
    execution in a new OpenTelemetry span.
*   **Context Propagation**: The `Tracing.withContext(context)` transformer is
    used with `.compose()` to ensure that the correct OpenTelemetry `Context`
    (and thus the correct parent span) is active when stream operators or
    subscriptions are executed, even across thread boundaries.

## Trace Hierarchy Example

A typical agent interaction might produce a trace hierarchy like the following:

```
invocation
└── invoke_agent my_agent
    ├── call_llm
    │   ├── tool_call [search_flights]
    │   └── tool_response [search_flights]
    └── call_llm
```

This shows:

1.  The overall `invocation` started by the `Runner`.
2.  The invocation of `my_agent`.
3.  The first `call_llm` made by `my_agent`.
4.  A `tool_call` to `search_flights` and its corresponding `tool_response`.
5.  A second `call_llm` made by `my_agent` to generate the final user response.

### Nested Agents

ADK supports nested agents, where one agent invokes another. If an agent has
sub-agents, it can transfer control to one of them using the built-in
`transfer_to_agent` tool. When `AgentA` calls `transfer_to_agent` to transfer
control to `AgentB`, the `invoke_agent AgentB` span will appear as a child of
the `invoke_agent AgentA` span, like so:

```
invocation
└── invoke_agent AgentA
    ├── call_llm
    │   ├── tool_call [transfer_to_agent]
    │   └── tool_response [transfer_to_agent]
    └── invoke_agent AgentB
        ├── call_llm
        └── ...
```

This structure allows you to see how `AgentA` delegated work to `AgentB`.

## Span Creation References

The following classes are the primary places where spans are created:

*   **`com.google.adk.runner.Runner`**: Initiates the top-level `invocation`
    span for `runAsync` and `runLive`.
*   **`com.google.adk.agents.BaseAgent`**: Creates the `invoke_agent
    <agent_name>` span for each agent execution.
*   **`com.google.adk.flows.llmflows.BaseLlmFlow`**: Creates the `call_llm` span
    when the LLM is invoked.
*   **`com.google.adk.flows.llmflows.Functions`**: Creates `tool_call [...]` and
    `tool_response [...]` spans when handling tool calls and responses.

## Configuration

**ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS**: This environment variable controls
whether LLM request/response content and tool arguments/responses are captured
in span attributes. It defaults to `true`. Set to `false` to exclude potentially
large or sensitive data from traces, in which case a `{}` JSON object will be
recorded instead.
