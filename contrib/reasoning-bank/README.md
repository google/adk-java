# reasoning-bank (contrib)

A Java implementation of **ReasoningBank**, a memory mechanism that lets agents learn from both
successful *and* failed trajectories and apply those lessons to new, similar tasks.

> Ouyang et al. "ReasoningBank: Scaling Agent Self-Evolving with Reasoning Memory", ICLR 2026.
> Paper: <https://arxiv.org/abs/2509.25140> · Blog: <https://research.google/blog/reasoningbank-enabling-agents-to-learn-from-experience/>
> Reference implementation: <https://github.com/google-research/reasoning-bank>

This module is **dependency-free** beyond ADK core: the LLM-backed judge and extractor use ADK's
`BaseLlm`, so they add no new model-client dependencies. Embedding-based retrieval (the one piece
that needs the Vertex SDK) is intentionally left to a future sibling module.

## What it provides

| Type | Purpose |
|---|---|
| `ReasoningMemoryItem` | A distilled memory item with the paper's `title` / `description` / `content` schema, plus `sourceTraceSuccessful` and provenance (`sourceTraceId`, `judgeVerdict`, `judgeConfidence`, `trust`) so a judge-minted item is auditable and evictable. |
| `ReasoningTrace` | A raw task trajectory (task, output, intermediate reasoning, success flag) kept for distillation. |
| `BaseReasoningBankService` / `InMemoryReasoningBankService` | Storage + retrieval (`storeMemoryItem`, `storeTrace`, `searchMemoryItems`). The in-memory impl uses bag-of-words keyword scoring — **not production-grade**; the reference uses embedding retrieval. |
| `TrajectoryJudge` (+ `LlmTrajectoryJudge`) | LLM-as-a-judge for the **judge** step. Returns a three-state `Verdict` (SUCCESS / FAILURE / INDETERMINATE). Ports the reference's asymmetric-strictness rubric: *mark failure when uncertain — a false success poisons future behavior.* |
| `MemoryExtractor` (+ `LlmMemoryExtractor`, `NoOpMemoryExtractor`) | The **extract** step. Routes by trajectory count/outcome to the `SUCCESSFUL_SI` / `FAILED_SI` / `PARALLEL_SI` prompts (generalized off WebArena), capped in code (3 single / 5 parallel) and never-throwing. |
| `ReasoningBankPlugin` | Wires the whole loop into the agent lifecycle: auto-retrieve (read-only) + opt-in consolidation. |
| `LoadReasoningMemoryTool` | Optional `FunctionTool` exposing retrieval to agents as `loadReasoningMemory(query)` for explicit/manual use. |

## The closed loop

`ReasoningBankPlugin` realizes the paper's continuous loop:

```
  retrieve  ──►  act (agent/env)  ──►  judge (LLM)  ──►  extract (LLM)  ──►  consolidate
  ▲                                                                           │
  └───────────────────────────────────────────────────────────────────────────┘
```

- **retrieve** — `beforeModelCallback` searches the bank for the latest user turn and injects the
  matches (read-only, always on).
- **act** — the agent runtime.
- **judge → extract → consolidate** — `afterRunCallback` self-assesses the trajectory
  (`TrajectoryJudge`), distills items (`MemoryExtractor`), and appends them (`storeMemoryItem`).
  This is **opt-in and triple-gated** (`autoConsolidate` + a judge + an extractor), because enabling
  writes turns a read-only system into a self-modifying one under an imperfect judge.

### Safety

Distilled memory is a stored, self-feeding channel — a poisoned item is re-injected on every future
retrieval — so the module defends the *integrity* of the write/inject path, not just accuracy:

- **De-privileged, fenced injection.** Retrieved memory is prepended as an *untrusted user content
  turn* inside an escaped fence, never a system instruction (a deliberate divergence from the
  reference, which injects into the system prompt).
- **Structural containment.** Each item field is sanitized so it cannot contribute a line boundary
  or an invisible control character: format/zero-width/bidi controls are stripped, all line and
  paragraph separators collapse to spaces, and fields are length-capped. Forged bullets, fake
  preambles, role markers, and confusable/fullwidth fences all collapse to inert inline data.
- **Abstain on non-run.** A judge that errors yields `INDETERMINATE` and mints nothing, so a
  non-run never fabricates a guardrail.
- **Bounded blast radius.** A per-run mint cap limits how much one (possibly wrong) verdict can
  write; failure-derived guardrails are trust-demoted at retrieval (they surface only when no
  success item matches the query).

These controls guarantee retrieved memory stays *untrusted data* and cannot escalate into a
system/instruction position. They do **not** stop a model from reading persuasive text inside an
item — that is the LLM's own instruction-hierarchy responsibility; the module's job is to never
present memory as authoritative.

## Not (yet) implemented

- **Embedding-based retrieval.** The in-memory service uses keyword matching; see the `screening`
  function in the reference repo for the Gemini / Qwen3 embedding recipe. The default retrieval cap
  is 3 items (the paper's k-ablation: more retrieved monotonically hurts).
- **MaTTS rollout fan-out and sequential refinement.** The parallel self-contrast *distillation*
  seam ships (`LlmMemoryExtractor` switches to `PARALLEL_SI` when given >1 trajectory), but running
  k same-task trajectories and the sequential prompts are future work.
- **Eviction policy by default.** Consolidation is append-only by default (faithful baseline). The
  `ConsolidationPolicy` SPI ships with an `identity()` (append-only) default and a
  `boundedByCreatedAt(n)` example; dedup/decay policies can drop in without core changes.

## Example

```java
BaseReasoningBankService bank = new InMemoryReasoningBankService();

// Retrieve-only: the agent draws on past memory, the bank is never written.
ReasoningBankPlugin retrieveOnly = new ReasoningBankPlugin(bank, "my-app");

// Or close the loop (opt-in): judge + distill + consolidate after each run.
ReasoningBankPlugin selfEvolving =
    new ReasoningBankPlugin(
        bank,
        "my-app",
        new LlmTrajectoryJudge(llm),
        new LlmMemoryExtractor(llm),
        /* autoConsolidate= */ true);

// Register the plugin with your Runner / App.
```
