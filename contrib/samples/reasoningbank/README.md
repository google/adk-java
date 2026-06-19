# ReasoningBank Sample

A live demo of the ADK ReasoningBank closed-loop pipeline: an LLM agent attempts an ungroundable
task, a judge evaluates the trajectory, and any failure is distilled into a guardrail memory item
stored in the bank for future retrieval.

## Prerequisites

- JDK 17+
- Maven 3.8+
- A Google AI Studio API key (set as `GOOGLE_API_KEY`)

```bash
export GOOGLE_API_KEY=your_key_here
```

Get a key at <https://aistudio.google.com/app/apikey>.

## Run

```bash
mvn -pl contrib/samples/reasoningbank exec:java
```

## What it does

The demo creates a `bugfixer` agent backed by `gemini-2.5-flash` with no tools and no access to
source code. It is given a failure-leaning task — listing every method in `com.acme.OrderService`
that can throw `NullPointerException` with exact line numbers — that the agent cannot ground because
it has no visibility into the codebase. After the agent responds, the `ReasoningBankPlugin`
automatically runs the judge (`LlmTrajectoryJudge`), which tends to return `FAILURE` on
ungroundable exhaustive claims. If a failure verdict is issued, the `LlmMemoryExtractor` distills
the trajectory into a guardrail memory item and stores it in the `InMemoryReasoningBankService`.
The demo then prints that distilled guardrail so you can see the complete closed loop: run ->
judge -> extract -> store.

## Note

This is an interactive demo requiring a live `GOOGLE_API_KEY`. For a fully deterministic,
offline proof of the closed loop that runs in CI without any API key, see
`ReasoningBankClosedLoopTest` in the `google-adk-reasoning-bank` module.
