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

The demo drives the ReasoningBank closed loop explicitly so each stage is visible in the output:

1. **Run**: A tool-less `BugFixAssistant` agent backed by `gemini-2.5-flash` attempts a
   failure-leaning task — listing every method in `com.acme.OrderService` that can throw
   `NullPointerException` with exact line numbers. The agent has no tools and no access to source
   code, so it cannot ground the exhaustive claim.

2. **Judge**: `LlmTrajectoryJudge` evaluates the trajectory and prints both the verdict outcome
   (`SUCCESS`, `FAILURE`, or `INDETERMINATE`) and the judge's full rationale (`thoughts`). This
   makes a real grounding failure distinguishable from a parse artifact. A strict rubric requiring
   completeness, grounding, and right-target tends to return `FAILURE` on ungroundable exhaustive
   claims.

3. **Extract**: `LlmMemoryExtractor` distills the judged trajectory into memory items labeled
   `GUARDRAIL (from failure)` or `strategy` based on the verdict, with title, description, and
   content printed for each.

4. **Store**: All distilled items are stored in `InMemoryReasoningBankService`.

5. **Retrieval precheck**: The first item is immediately queried by its own title, confirming it is
   retrievable. This uses the item's exact title as the probe (not task-literal keywords), which
   guarantees a match against the bag-of-words index.

The demo also wires a retrieve-only `ReasoningBankPlugin(bank, APP)` into the `InMemoryRunner` to
show that the plugin is ready to inject past memory into future runs — it is a no-op on this first
empty-bank run, which is expected.

## Production wiring

In production, register a fully wired plugin to make consolidation automatic:

```java
new ReasoningBankPlugin(bank, appName, judge, extractor, /* autoConsolidate= */ true)
```

This makes the judge → extract → store pipeline run automatically after every agent invocation,
without explicit orchestration. The behavior is proven deterministically (no API key required) by
`ReasoningBankClosedLoopTest` in the `google-adk-reasoning-bank` module.

## Note

This is an interactive demo requiring a live `GOOGLE_API_KEY`. For a fully deterministic,
offline proof of the closed loop that runs in CI without any API key, see
`ReasoningBankClosedLoopTest` in the `google-adk-reasoning-bank` module.
