# reasoning-bank (contrib)

A Java implementation of the retrieval & storage primitives from **ReasoningBank**, a memory
mechanism that lets agents learn from both successful *and* failed trajectories.

> Ouyang et al. "ReasoningBank: Scaling Agent Self-Evolving with Reasoning Memory", ICLR 2026.
> Paper: <https://arxiv.org/abs/2509.25140> · Blog: <https://research.google/blog/reasoningbank-enabling-agents-to-learn-from-experience/>
> Reference implementation: <https://github.com/google-research/reasoning-bank>

## What it provides

| Type | Purpose |
|---|---|
| `ReasoningMemoryItem` | A distilled memory item with the paper's `title` / `description` / `content` schema, plus `sourceTraceSuccessful` so preventative lessons from failed trajectories are first-class. |
| `ReasoningTrace` | A raw task trajectory (task, output, intermediate reasoning, success flag) kept for later distillation. |
| `BaseReasoningBankService` | Storage/retrieval contract: `storeMemoryItem`, `storeTrace`, `searchMemoryItems`. |
| `InMemoryReasoningBankService` | Prototype in-memory implementation using bag-of-words keyword scoring. **Not production-grade** — the reference implementation uses embedding-based retrieval. |
| `MemoryExtractor` (+ `NoOpMemoryExtractor`) | SPI for the "judge & extract" step that turns trajectories into memory items. LLM-backed extractors are intentionally out of scope for this module. |
| `LoadReasoningMemoryTool` | `FunctionTool` exposing retrieval to agents as `loadReasoningMemory(query)`. |

## The closed loop

The paper describes a continuous loop; this module covers the storage and retrieval half:

```
  retrieve  ──►  act (agent/env)  ──►  judge (LLM)  ──►  extract (LLM)  ──►  consolidate
  ▲                                                                           │
  └───────────────────────────────────────────────────────────────────────────┘
```

- `searchMemoryItems` implements **retrieve**.
- The agent runtime handles **act**.
- **Judge** and **extract** are represented by the `MemoryExtractor` SPI; plug in an LLM-backed
  extractor to realize them.
- `storeMemoryItem` implements **consolidate** (append).

## Not (yet) implemented

- **Embedding-based retrieval.** The in-memory service uses keyword matching; see the `screening`
  function in the reference repo for the Gemini / Qwen3 embedding recipe.
- **Memory-aware Test-Time Scaling (MaTTS).** The `MemoryExtractor.extract` method accepts a list
  of trajectories so that parallel self-contrast distillation can be added later without an API
  break, but no MaTTS driver ships here.
- **LLM-as-a-judge** and **LLM-based extraction prompts** (`SUCCESSFUL_SI`, `FAILED_SI`,
  `PARALLEL_SI`, `SEQUENTIAL_PROMPT` in the reference repo).

## Example

```java
BaseReasoningBankService bank = new InMemoryReasoningBankService();

bank.storeMemoryItem(
        "my-app",
        ReasoningMemoryItem.builder()
            .id("pitfall-1")
            .title("Avoid infinite scroll traps")
            .description("Verify page identifier before loading more results.")
            .content(
                "Before clicking 'Load more', cross-reference the current page id with active "
                    + "filters to ensure the list isn't paginated prematurely.")
            .tags(ImmutableList.of("web", "pagination"))
            .sourceTraceSuccessful(false)
            .build())
    .blockingAwait();

LoadReasoningMemoryTool tool = new LoadReasoningMemoryTool(bank, "my-app");
// attach `tool` to your agent's tool list
```
