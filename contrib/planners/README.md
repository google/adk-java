# ADK Planners (`google-adk-planners`)

Pluggable planner implementations for the ADK `PlannerAgent`. This module provides six planning strategies for dynamically orchestrating sub-agent execution at runtime — from simple sequential dispatch to sophisticated goal-oriented planning with adaptive replanning.

## Table of Contents

1. [Overview & Quick Start](#1-overview--quick-start)
2. [Architecture](#2-architecture)
3. [Simple Planners](#3-simple-planners)
4. [Goal-Oriented Action Planning (GOAP)](#4-goal-oriented-action-planning-goap)
5. [Peer-to-Peer (P2P) Planner](#5-peer-to-peer-p2p-planner)
6. [Choosing a Planner](#6-choosing-a-planner)
7. [Advanced Topics](#7-advanced-topics)
8. [Testing](#8-testing)
9. [Package Reference](#9-package-reference)
10. [License](#10-license)

---

## 1. Overview & Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-planners</artifactId>
    <version>${adk.version}</version>
</dependency>
```

### Quick Start

```java
PlannerAgent agent = PlannerAgent.builder()
    .name("pipeline")
    .description("Runs agents in sequence")
    .subAgents(agentA, agentB, agentC)
    .planner(new SequentialPlanner())
    .build();
```

The `PlannerAgent` delegates execution decisions to a `Planner` strategy. Swap the planner to change how agents are orchestrated — the sub-agents stay the same.

### Planners at a Glance

| Planner | Package | Execution Model | LLM Required | Primary Use Case |
|---------|---------|----------------|:---:|-----------------|
| `SequentialPlanner` | `planner` | One at a time, in order | No | Fixed pipelines, ETL steps |
| `ParallelPlanner` | `planner` | All at once | No | Independent fan-out tasks |
| `LoopPlanner` | `planner` | Cyclic, repeating | No | Review/revision cycles |
| `SupervisorPlanner` | `planner` | LLM selects next agent(s) | Yes | Open-ended task delegation |
| `GoalOrientedPlanner` | `planner.goap` | Dependency-resolved groups | No | Workflows with input/output contracts |
| `P2PPlanner` | `planner.p2p` | Reactive dynamic activation | No | Collaborative refinement loops |

---

## 2. Architecture

### Core Abstractions

The module is built on four core types in `com.google.adk.agents`:

```
PlannerAgent ──owns──> Planner (strategy interface)
     │                    │
     │                    └─returns─> PlannerAction (sealed: what to do next)
     │
     └──creates──> PlanningContext (state + agents + events)
```

**`Planner`** — Strategy interface with a three-step lifecycle:
- `init(PlanningContext)` — called once before the loop starts (default no-op; override for setup like building dependency graphs)
- `firstAction(PlanningContext)` — returns the first action to execute
- `nextAction(PlanningContext)` — returns the next action after agents execute and state updates

All methods return `Single<PlannerAction>`, supporting both synchronous planners (wrap in `Single.just()`) and asynchronous planners that call an LLM.

**`PlannerAction`** — Sealed interface with four variants representing what the planner wants to happen next:

```java
public sealed interface PlannerAction
    permits RunAgents, Done, DoneWithResult, NoOp {

  record RunAgents(ImmutableList<BaseAgent> agents) implements PlannerAction {}
  record Done() implements PlannerAction {}
  record DoneWithResult(String result) implements PlannerAction {}
  record NoOp() implements PlannerAction {}
}
```

**`PlanningContext`** — The planner's view of the world:
- `state()` — session state map (`Map<String, Object>`) shared across all agents
- `events()` — all events produced so far in the session
- `availableAgents()` — the sub-agents the planner can select from
- `userContent()` — the user message that initiated this invocation (if any)
- `findAgent(name)` — look up an agent by name (throws `IllegalArgumentException` if not found)

**`PlannerAgent`** — A `BaseAgent` that orchestrates the planning loop. Built via `PlannerAgent.builder()` with a required `planner(...)` and optional `maxIterations(int)` (default: 100).

### The Planning Loop

```
┌────────────────────────────────────────────────────────────────┐
│                       PlannerAgent.runAsyncImpl()               │
│                                                                │
│   planner.init(context)                                        │
│       │                                                        │
│       v                                                        │
│   planner.firstAction(context)                                 │
│       │                                                        │
│       v                                                        │
│   ┌───────────────────────────────────┐                        │
│   │ action instanceof ...             │                        │
│   ├───────────────────────────────────┤                        │
│   │ Done         → stop (empty)       │                        │
│   │ DoneWithResult → emit text event  │                        │
│   │ NoOp         → skip to nextAction │──┐                     │
│   │ RunAgents    → execute agent(s)   │  │                     │
│   └───────────────────┬───────────────┘  │                     │
│                       │                  │                      │
│                       v                  │                      │
│              planner.nextAction(context) ◄──┘                  │
│                       │                                        │
│                       └── loop until Done or maxIterations ──┘ │
└────────────────────────────────────────────────────────────────┘
```

**Action semantics within the loop:**

| Action | Behavior |
|--------|----------|
| `RunAgents` (1 agent) | Dispatches to `agent.runAsync(invocationContext)` |
| `RunAgents` (N agents) | Dispatches all in parallel via `Flowable.merge(...)` |
| `Done` | Emits nothing; loop terminates |
| `DoneWithResult` | Emits a single text `Event` with the result string |
| `NoOp` | Skips agent execution; immediately calls `planner.nextAction()` |

### Reactive Execution Model

The module uses RxJava 3 throughout:
- Planners return `Single<PlannerAction>` — a single async value
- Agent execution produces `Flowable<Event>` — a stream of events
- The loop chains actions via `concatWith(Flowable.defer(...))` for lazy sequential composition

This means planners can be purely synchronous (e.g., `SequentialPlanner` uses `Single.just(...)`) or genuinely asynchronous (e.g., `SupervisorPlanner` makes an LLM call that returns a `Single`).

---

## 3. Simple Planners

Four ready-to-use planners for common orchestration patterns. All are in `com.google.adk.planner`.

### SequentialPlanner

Runs sub-agents one at a time in registration order. No configuration needed.

```java
PlannerAgent agent = PlannerAgent.builder()
    .name("pipeline")
    .subAgents(extractAgent, transformAgent, loadAgent)
    .planner(new SequentialPlanner())
    .build();
```

```
Execution: extractAgent ──> transformAgent ──> loadAgent ──> Done
```

Internally uses a cursor that increments after each agent. Returns `Done` when the cursor exceeds the agent count.

### ParallelPlanner

Runs all sub-agents in parallel on the first action, then completes immediately.

```java
PlannerAgent agent = PlannerAgent.builder()
    .name("fanout")
    .subAgents(searchWeb, searchDocs, searchCode)
    .planner(new ParallelPlanner())
    .build();
```

```
Execution: [searchWeb, searchDocs, searchCode] ──> Done
                   (all in parallel)
```

The simplest planner — stateless, no configuration. `firstAction` returns `RunAgents(allAgents)`, `nextAction` always returns `Done`.

### LoopPlanner

Cycles through sub-agents repeatedly, stopping when the cycle count is reached or an escalate event is detected.

```java
PlannerAgent agent = PlannerAgent.builder()
    .name("reviewer")
    .subAgents(draftAgent, reviewAgent)
    .planner(new LoopPlanner(3))  // max 3 cycles
    .build();
```

```
Execution: draftAgent ──> reviewAgent ──> draftAgent ──> reviewAgent ──> ... ──> Done
           ├─── cycle 1 ───┤              ├─── cycle 2 ───┤
```

**Termination conditions:**
1. `cycleCount >= maxCycles` — hard limit on cycles
2. Escalate event — if the last event has `event.actions().escalate() == true`, the loop stops

### SupervisorPlanner

Uses an LLM to dynamically decide which agent(s) to run next. The LLM receives a prompt with available agents, current state, recent events, and its own decision history.

```java
PlannerAgent agent = PlannerAgent.builder()
    .name("supervisor")
    .subAgents(researchAgent, analyzeAgent, writeAgent)
    .planner(new SupervisorPlanner(llm, "You coordinate a research team.", 20))
    .build();
```

**Constructor variants:**
- `SupervisorPlanner(BaseLlm llm)` — minimal; no system instruction, default maxEvents=20
- `SupervisorPlanner(BaseLlm llm, String systemInstruction)` — custom system prompt
- `SupervisorPlanner(BaseLlm llm, String systemInstruction, int maxEvents)` — full control

**Prompt structure** (built automatically):
1. Available agents with descriptions
2. Current state keys
3. Recent events (sliding window of `maxEvents`, default 20)
4. Decision history (all prior decisions in order)
5. Original user request (if available)

**LLM response parsing:**
- `"DONE"` → `PlannerAction.Done`
- `"DONE: <summary>"` → `PlannerAction.DoneWithResult(summary)`
- `"agentName"` → `PlannerAction.RunAgents(agent)`
- `"agent1,agent2"` → `PlannerAction.RunAgents(agent1, agent2)` (parallel)
- Unknown agent name → falls back to `Done` (with warning log)
- LLM call failure → falls back to `Done` (via `onErrorReturn`)

### Simple Planners Comparison

| | SequentialPlanner | ParallelPlanner | LoopPlanner | SupervisorPlanner |
|-|:-:|:-:|:-:|:-:|
| **Internal State** | cursor | none | cursor + cycleCount | decisionHistory |
| **Configuration** | none | none | maxCycles | llm, systemInstruction, maxEvents |
| **Deterministic** | Yes | Yes | Yes* | No |
| **LLM Required** | No | No | No | Yes |
| **Termination** | All agents run | After first action | maxCycles or escalate | LLM says DONE |

*\* LoopPlanner is deterministic in ordering but the escalation check depends on agent behavior.*

---

## 4. Goal-Oriented Action Planning (GOAP)

The GOAP subsystem (`com.google.adk.planner.goap`) resolves agent execution order by analyzing input/output dependencies between agents. Given a target goal, it computes which agents need to run and in what order, grouping independent agents for parallel execution.

This approach is inspired by Goal-Oriented Action Planning from game AI, adapted for agent orchestration: instead of game-world states and character actions, the "world state" is session state keys and "actions" are sub-agents with declared I/O contracts.

### AgentMetadata

Each agent declares what state keys it reads (inputs) and writes (output):

```java
public record AgentMetadata(
    String agentName,                  // must match BaseAgent.name()
    ImmutableList<String> inputKeys,   // state keys the agent reads
    String outputKey                   // state key the agent produces
) {}
```

Example — a horoscope pipeline:

```java
List<AgentMetadata> metadata = List.of(
    new AgentMetadata("personExtractor", ImmutableList.of("prompt"), "person"),
    new AgentMetadata("signExtractor",   ImmutableList.of("prompt"), "sign"),
    new AgentMetadata("horoscopeGen",    ImmutableList.of("person", "sign"), "horoscope"),
    new AgentMetadata("writer",          ImmutableList.of("person", "horoscope"), "writeup")
);
```

### Dependency Graph

`GoalOrientedSearchGraph` builds an immutable dependency graph from the metadata:

```
                    prompt (precondition)
                   /                     \
          personExtractor          signExtractor
              |  "person"              |  "sign"
              |                        |
              └───────┬────────────────┘
                      v
               horoscopeGen
                  |  "horoscope"
                  |
                  v
               writer ──> "writeup" (goal)
```

The graph maintains two mappings:
- `outputKey → agentName` — which agent produces each output
- `outputKey → inputKeys` — what dependencies each output requires

Duplicate output keys across agents cause an `IllegalArgumentException`.

### Search Strategies

The `SearchStrategy` interface defines how the dependency graph is traversed to produce execution groups:

```java
public interface SearchStrategy {
    ImmutableList<ImmutableList<String>> searchGrouped(
        GoalOrientedSearchGraph graph,
        List<AgentMetadata> metadata,
        Collection<String> preconditions,
        String goal);
}
```

Two implementations are provided:

#### DfsSearchStrategy (default)

**Backward-chaining depth-first search.** Starts at the goal and recursively resolves dependencies. Uses a `visiting` set for cycle detection and a `satisfied` set for precondition skipping.

```
Goal: "writeup"
  └─ needs "person", "horoscope"
       └─ "horoscope" needs "person", "sign"
            └─ "person" needs "prompt" (precondition ✓)
            └─ "sign" needs "prompt" (precondition ✓)
       └─ "person" (already resolved)

Flat order: personExtractor, signExtractor, horoscopeGen, writer
```

#### AStarSearchStrategy

**Forward A\* search** from preconditions toward the goal. Uses a priority queue ordered by f-score:
- **g** = number of agents activated so far (uniform cost)
- **h** = admissible heuristic counting unsatisfied dependencies reachable backward from the goal

The heuristic performs a breadth-first backward traversal from the goal, counting keys not yet in the activated set. Since each unsatisfied key requires at least one agent, this never overestimates.

#### Parallel Level Assignment

Both strategies ultimately use `DependencyGraphSearch.assignParallelLevels()` to group agents for parallel execution. Each agent's level is computed as:

```
level(agent) = 1 + max(level(dependency_agents))
```

Agents at the same level have no mutual dependencies and run in parallel:

```
Level 0: [personExtractor, signExtractor]  ← independent, run in parallel
Level 1: [horoscopeGen]                    ← waits for level 0
Level 2: [writer]                          ← waits for level 1
```

Both DFS and A\* produce identical groupings for any valid DAG — this is verified by cross-strategy equivalence tests.

### GoalOrientedPlanner

The planner that ties it all together:

```java
// Default: DFS search + Ignore policy
GoalOrientedPlanner planner = new GoalOrientedPlanner("writeup", metadata);

// With A* search and Replan policy
GoalOrientedPlanner planner = new GoalOrientedPlanner(
    "writeup", metadata, new AStarSearchStrategy(), new ReplanPolicy.Replan(3));

PlannerAgent agent = PlannerAgent.builder()
    .name("horoscope")
    .subAgents(personExtractor, signExtractor, horoscopeGen, writer)
    .planner(planner)
    .build();
```

**Lifecycle:**
1. `init()` — builds the dependency graph and computes execution groups via the search strategy. Keys already present in session state are treated as satisfied preconditions (skipping agents that produce them).
2. `firstAction()` — returns the first group of agents to run
3. `nextAction()` — checks for missing outputs from the previous group, applies the replan policy if needed, then returns the next group

**Constructor variants:**
- `GoalOrientedPlanner(goal, metadata)` — DFS + Ignore (default)
- `GoalOrientedPlanner(goal, metadata, validateOutputs)` — DFS + FailStop (true) or Ignore (false)
- `GoalOrientedPlanner(goal, metadata, searchStrategy, replanPolicy)` — full control

### ReplanPolicy

A sealed interface governing how the planner reacts when agents don't produce their expected outputs:

```java
public sealed interface ReplanPolicy permits FailStop, Replan, Ignore {
    record FailStop() implements ReplanPolicy {}
    record Replan(int maxAttempts) implements ReplanPolicy {}  // maxAttempts >= 1
    record Ignore() implements ReplanPolicy {}
}
```

| Policy | On Missing Output | Attempt Tracking | Termination |
|--------|-------------------|:---:|-------------|
| `Ignore` (default) | Proceeds with remaining plan | No | Normal completion |
| `FailStop` | Halts immediately | No | `DoneWithResult` with error listing missing outputs |
| `Replan(n)` | Rebuilds plan from current state | Yes, resets on success | `DoneWithResult` after n consecutive failures |

**Replanning flow:**

```
                    nextAction() called
                          │
                   ┌──────┴──────┐
                   │  outputs     │
                   │  missing?    │
                   └──────┬──────┘
                     No   │   Yes
                     │    │    │
              reset  │    │    ├── Ignore ──> proceed with current plan
              replan │    │    ├── FailStop ─> DoneWithResult(error)
              count  │    │    └── Replan ──> replanCount < max?
                     │    │                     │         │
                     v    │                    Yes        No
                  select  │                     │         │
                  next    │              rebuild plan   DoneWithResult
                  group   │              from current   (exhausted)
                          │              state
                          v
```

The replan counter tracks **consecutive** failures. It resets to zero whenever a group completes successfully.

### Council Topology Example

A realistic 9-agent pipeline tested extensively in the codebase:

```
                     initial_response
                    /        |        \
           peer_ranking  agreement   disagreement
               |         _analysis    _analysis
               |              |           |
        aggregate_rankings  aggregate   aggregate
               |           _agreements _disagreements
               |              |           |
               └──── final_synthesis ─────┘
                          |
                   council_summary
```

This produces 4 execution groups:
1. `[initial_response]`
2. `[peer_ranking, agreement_analysis, disagreement_analysis]`
3. `[final_synthesis, aggregate_rankings, aggregate_agreements, aggregate_disagreements]`
4. `[council_summary]`

---

## 5. Peer-to-Peer (P2P) Planner

The P2P planner (`com.google.adk.planner.p2p`) takes a fundamentally different approach from GOAP: instead of computing an execution plan upfront, agents activate dynamically as their input dependencies become available in session state.

### Concepts

- **No upfront plan** — agents are not pre-ordered; they activate when their inputs appear
- **Parallel activation** — multiple agents can activate simultaneously when their inputs are satisfied
- **Iterative refinement** — when an agent produces a new or changed output, downstream agents re-execute
- **Value-change detection** — `Objects.equals()` comparison prevents spurious re-activation when an output is written but unchanged

### AgentActivator

Each agent is wrapped in an `AgentActivator` that tracks its activation state:

```
                 ┌───────────────┐
                 │ AgentActivator │
                 ├───────────────┤
    init ──────> │ shouldExecute  │ = true
                 │ executing      │ = false
                 └───────┬───────┘
                         │
          canActivate(state)?
          = !executing && shouldExecute
            && all inputKeys present in state
                         │
                    ┌────┴────┐
                    │   Yes   │
                    └────┬────┘
                         │
            startExecution()
            executing=true, shouldExecute=false
                         │
                    (agent runs)
                         │
            finishExecution()
            executing=false
                         │
            onStateChanged(key)?
            if key in inputKeys: shouldExecute=true
                         │
                    (may re-activate)
```

### P2PPlanner Usage

```java
List<AgentMetadata> metadata = List.of(
    new AgentMetadata("literature",  ImmutableList.of("topic"), "researchFindings"),
    new AgentMetadata("hypothesis",  ImmutableList.of("topic", "researchFindings"), "hypothesis"),
    new AgentMetadata("critic",      ImmutableList.of("topic", "hypothesis"), "critique"),
    new AgentMetadata("scorer",      ImmutableList.of("topic", "hypothesis", "critique"), "score")
);

// Exit when score is high enough
P2PPlanner planner = new P2PPlanner(metadata, 20,
    (state, count) -> {
        Object score = state.get("score");
        return score instanceof Number && ((Number) score).doubleValue() >= 0.85;
    });

PlannerAgent agent = PlannerAgent.builder()
    .name("research")
    .subAgents(literatureAgent, hypothesisAgent, criticAgent, scorerAgent)
    .planner(planner)
    .build();
```

**Constructor variants:**
- `P2PPlanner(metadata, maxInvocations)` — exits only on max invocations
- `P2PPlanner(metadata, maxInvocations, exitCondition)` — custom `BiPredicate<Map<String, Object>, Integer>`

### Termination

Three conditions, checked in this order:
1. **Exit condition** — `exitCondition.test(state, invocationCount)` returns true
2. **Max invocations** — `invocationCount >= maxInvocations`
3. **No activatable agents** — no agent can activate (all are waiting for inputs or already ran without new inputs)

### Iterative Refinement

When an agent produces a changed output value, all agents that have that key in their `inputKeys` get marked for re-execution:

```
Wave 1: literature (topic present) → produces researchFindings
Wave 2: hypothesis (topic + researchFindings) → produces hypothesis
Wave 3: critic (topic + hypothesis) → produces critique
Wave 4: scorer (topic + hypothesis + critique) → produces score (0.6)

         ← score too low, critic's output changes next round →

Wave 5: hypothesis re-activates (critique changed) → updated hypothesis
Wave 6: critic re-activates (hypothesis changed) → updated critique
Wave 7: scorer re-activates → produces score (0.87) → exit condition met
```

Only **actual value changes** trigger re-activation. If an agent produces the same value (checked via `Objects.equals()`), downstream agents are not notified.

### GOAP vs P2P

| Dimension | GOAP | P2P |
|-----------|------|-----|
| **Plan computation** | Upfront (at init) | None; reactive |
| **Execution order** | Pre-determined groups | Dynamic waves |
| **Parallelism** | Agents grouped by dependency level | Agents activate when inputs ready |
| **Failure handling** | ReplanPolicy (Ignore/FailStop/Replan) | N/A (agents simply don't activate) |
| **Re-execution** | No (each agent runs once) | Yes (on input value change) |
| **State-change sensitivity** | Checks presence of output keys | Checks both presence and value equality |
| **Best for** | Known dependency DAGs, one-shot workflows | Iterative refinement, collaborative loops |

---

## 6. Choosing a Planner

### Decision Flowchart

```
                          ┌─────────────────────┐
                          │  Are agents fully    │
                          │  independent?        │
                          └──────────┬──────────┘
                              Yes    │    No
                               │     │     │
                               v     │     v
                        ParallelPlanner   ┌─────────────────────┐
                                          │  Is the execution   │
                                          │  order fixed?       │
                                          └──────────┬──────────┘
                                              Yes    │    No
                                               │     │     │
                                               v     │     v
                                      SequentialPlanner   ┌─────────────────────┐
                                                          │  Need iterative     │
                                                          │  cycles?            │
                                                          └──────────┬──────────┘
                                                              Yes    │    No
                                                               │     │     │
                                                               v     │     v
                                                       LoopPlanner   ┌─────────────────────┐
                                                                     │  Should an LLM      │
                                                                     │  decide dynamically? │
                                                                     └──────────┬──────────┘
                                                                          Yes   │    No
                                                                           │    │     │
                                                                           v    │     v
                                                               SupervisorPlanner│  ┌─────────────────────┐
                                                                                │  │  Do agents have     │
                                                                                │  │  I/O dependencies?  │
                                                                                │  └──────────┬──────────┘
                                                                                │       Yes   │
                                                                                │        │    │
                                                                                │        v    │
                                                                                │   ┌────────┴────────┐
                                                                                │   │  Need iterative │
                                                                                │   │  refinement?    │
                                                                                │   └────────┬────────┘
                                                                                │     No     │    Yes
                                                                                │      │     │     │
                                                                                │      v     │     v
                                                                                │ GoalOrientedPlanner
                                                                                │            │  P2PPlanner
                                                                                │            │
                                                                                └────────────┘
```

### Use Case Catalog

| Scenario | Recommended Planner | Why |
|----------|-------------------|-----|
| ETL pipeline (extract → transform → load) | `SequentialPlanner` | Fixed order, each step depends on the previous |
| Fan-out aggregation (search multiple sources) | `ParallelPlanner` | Independent tasks, no ordering needed |
| Draft-review cycles | `LoopPlanner` | Iterative passes with escalation-based exit |
| Open-ended task delegation | `SupervisorPlanner` | LLM decides what to do based on context |
| Multi-step workflow with dependencies | `GoalOrientedPlanner` | Agents declare I/O; planner resolves order automatically |
| Research collaboration with critic feedback | `P2PPlanner` | Agents re-execute as inputs refine |

### Composability

`PlannerAgent` is itself a `BaseAgent`, so planners can be nested. A GOAP planner can orchestrate sub-agents where one of those sub-agents is itself a `PlannerAgent` with a `LoopPlanner` inside:

```java
// Inner: draft-review loop
PlannerAgent reviewLoop = PlannerAgent.builder()
    .name("reviewLoop")
    .subAgents(draftAgent, reviewAgent)
    .planner(new LoopPlanner(3))
    .build();

// Outer: GOAP pipeline that includes the review loop
List<AgentMetadata> metadata = List.of(
    new AgentMetadata("research",   ImmutableList.of("topic"), "findings"),
    new AgentMetadata("reviewLoop", ImmutableList.of("findings"), "reviewed"),
    new AgentMetadata("publish",    ImmutableList.of("reviewed"), "published")
);

PlannerAgent pipeline = PlannerAgent.builder()
    .name("pipeline")
    .subAgents(researchAgent, reviewLoop, publishAgent)
    .planner(new GoalOrientedPlanner("published", metadata))
    .build();
```

---

## 7. Advanced Topics

### Implementing a Custom Planner

Implement the `Planner` interface:

```java
public class PriorityPlanner implements Planner {

    @Override
    public void init(PlanningContext context) {
        // Optional: build data structures, analyze agents
    }

    @Override
    public Single<PlannerAction> firstAction(PlanningContext context) {
        // Return the first action
        BaseAgent highest = selectHighestPriority(context);
        return Single.just(new PlannerAction.RunAgents(highest));
    }

    @Override
    public Single<PlannerAction> nextAction(PlanningContext context) {
        // Inspect updated state and decide
        if (isGoalMet(context.state())) {
            return Single.just(new PlannerAction.Done());
        }
        return Single.just(new PlannerAction.RunAgents(selectHighestPriority(context)));
    }
}
```

For async planners (e.g., calling an LLM), return the `Single` from the async call:

```java
@Override
public Single<PlannerAction> nextAction(PlanningContext context) {
    return llm.generateContent(request, false)
        .lastOrError()
        .map(response -> parseActionFromResponse(response));
}
```

### Session State as World State

Session state (`PlanningContext.state()`) is the shared "world state" that connects agents and planners:

- **Agents write** to state via event `stateDelta` — when an agent emits an event, the state delta is applied to the session
- **Planners read** state to make decisions — `context.state()` reflects the current state after all prior agents have run
- **Keys are the contract** — `AgentMetadata` declares which state keys an agent reads and writes; both GOAP and P2P planners use these declarations for dependency resolution

### Error Handling and Resilience

**SupervisorPlanner:**
- LLM call failures are caught via `onErrorReturn` and fall back to `Done` (with a warning log)
- Unknown agent names in LLM responses fall back to `Done` (with a warning log)

**GoalOrientedPlanner:**
- Unresolvable dependencies throw `IllegalStateException` at `init` time
- Circular dependencies are detected and throw `IllegalStateException`
- Missing outputs after agent execution are handled by `ReplanPolicy`

**PlannerAgent:**
- `maxIterations` (default 100) prevents infinite planning loops

### maxIterations vs maxInvocations vs maxCycles

Three different bounds apply at different levels:

| Bound | Where | Default | What It Limits |
|-------|-------|:---:|----------------|
| `maxIterations` | `PlannerAgent.builder()` | 100 | Total planning loop iterations (across all planners) |
| `maxInvocations` | `P2PPlanner` constructor | (required) | Total agent invocations in P2P planning |
| `maxCycles` | `LoopPlanner` constructor | (required) | Complete cycles through the agent list |

### Callbacks

`PlannerAgent.Builder` inherits `beforeAgentCallback` and `afterAgentCallback` from `BaseAgent.Builder`:

```java
PlannerAgent agent = PlannerAgent.builder()
    .name("pipeline")
    .subAgents(agentA, agentB)
    .planner(new SequentialPlanner())
    .beforeAgentCallback(List.of((ctx, agentName) -> {
        // Called before each sub-agent runs
        return Single.just(true); // return false to skip the agent
    }))
    .build();
```

---

## 8. Testing

### Test Stack

| Component | Library |
|-----------|---------|
| Test framework | JUnit 5 (`@Test`, `@Nested`) |
| Assertions | Google Truth (`assertThat(...).containsExactly(...)`) |
| Mocking | Mockito (used for `BaseLlm` in SupervisorPlanner tests) |
| Reactive testing | RxJava 3 `.blockingGet()` for synchronous test execution |

### Test Organization

Tests mirror the source package structure:

```
src/test/java/com/google/adk/
├── agents/
│   └── PlannerAgentTest.java           # PlannerAgent integration
└── planner/
    ├── SequentialPlannerTest.java       # Sequential execution
    ├── ParallelPlannerTest.java        # Parallel execution
    ├── LoopPlannerTest.java            # Cyclic execution + escalation
    ├── SupervisorPlannerTest.java      # LLM-driven selection + prompt building
    ├── goap/
    │   ├── AStarSearchStrategyTest.java     # A* graph traversal
    │   ├── GoalOrientedPlannerTest.java     # GOAP planning + dependency resolution
    │   ├── ReplanningTest.java              # Replan policy behavior
    │   └── CouncilTopologyTest.java         # 9-agent DAG (GOAP behavior)
    └── p2p/
        ├── P2PPlannerTest.java              # Reactive activation + refinement
        └── P2PCouncilTopologyTest.java      # 9-agent DAG (P2P behavior)
```

Larger test suites use `@Nested` classes to group related scenarios. For example, `CouncilTopologyTest` organizes into:
- `GoapPlanningBehavior` — group structure and precondition skipping
- `AdaptiveGoapReplanning` — replan policy scenarios
- `EdgeCases` — partial failures, policy comparisons

### Test Patterns

**Minimal test agent** — all tests use a `SimpleTestAgent` that extends `BaseAgent` and returns `Flowable.empty()`:

```java
class SimpleTestAgent extends BaseAgent {
    SimpleTestAgent(String name) { super(name, name + " description", ImmutableList.of()); }
    @Override protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return Flowable.empty();
    }
}
```

**Context creation** — tests create a `PlanningContext` with `InMemorySessionService` and a `ConcurrentHashMap` for state:

```java
PlanningContext context = createPlanningContext(agents, new ConcurrentHashMap<>());
```

**Plan walking** — tests walk the planning loop by calling `firstAction`/`nextAction` until `Done`:

```java
PlannerAction action = planner.firstAction(context).blockingGet();
while (action instanceof PlannerAction.RunAgents runAgents) {
    simulateSuccess(context, agentNames(runAgents));
    action = planner.nextAction(context).blockingGet();
}
```

**State injection** — tests simulate agent output by directly updating `context.state()`:

```java
context.state().put("person", "Alice");
context.state().put("sign", "Aries");
```

**Strategy equivalence** — A\* vs DFS equivalence is verified on multiple topologies:

```java
assertThat(astarGroups).isEqualTo(dfsGroups);
```

### Test Coverage

| Test Class | Focus | Notable Scenarios |
|-----------|-------|-------------------|
| `PlannerAgentTest` | Integration loop | State sharing, maxIterations, NoOp handling |
| `SequentialPlannerTest` | Ordering | Cursor reset, empty agents |
| `ParallelPlannerTest` | Fan-out | Single agent, empty agents |
| `LoopPlannerTest` | Cycling | maxCycles, escalate event detection |
| `SupervisorPlannerTest` | LLM interaction | Prompt construction, decision history, error fallback |
| `AStarSearchStrategyTest` | Graph search | Linear, diamond, deep chains, cycle detection, DFS equivalence |
| `GoalOrientedPlannerTest` | GOAP planning | Dependency resolution, parallel grouping, output validation |
| `ReplanningTest` | Failure handling | Counter reset, max attempts, policy comparison |
| `CouncilTopologyTest` | Complex GOAP | 9-agent DAG, partial failure, cross-strategy equivalence |
| `P2PPlannerTest` | Reactive activation | Value-change detection, exit conditions, maxInvocations |
| `P2PCouncilTopologyTest` | Complex P2P | Wave activation, iterative refinement, termination |

### Running Tests

```bash
mvn test -pl contrib/planners
```

---

## 9. Package Reference

### Source Layout

```
contrib/planners/src/main/java/com/google/adk/
├── agents/
│   ├── Planner.java
│   ├── PlannerAction.java
│   ├── PlannerAgent.java
│   └── PlanningContext.java
└── planner/
    ├── SequentialPlanner.java
    ├── ParallelPlanner.java
    ├── LoopPlanner.java
    ├── SupervisorPlanner.java
    ├── goap/
    │   ├── GoalOrientedPlanner.java
    │   ├── AgentMetadata.java
    │   ├── SearchStrategy.java
    │   ├── DfsSearchStrategy.java
    │   ├── AStarSearchStrategy.java
    │   ├── DependencyGraphSearch.java
    │   ├── GoalOrientedSearchGraph.java
    │   └── ReplanPolicy.java
    └── p2p/
        ├── P2PPlanner.java
        └── AgentActivator.java
```

### Class Index

| Package | Class | Type | Purpose |
|---------|-------|------|---------|
| `agents` | `Planner` | interface | Strategy for selecting next agent(s) |
| `agents` | `PlannerAction` | sealed interface | Four-variant action result type |
| `agents` | `PlannerAgent` | class | Orchestrating agent that runs the planning loop |
| `agents` | `PlanningContext` | class | State, events, and agents available to planners |
| `planner` | `SequentialPlanner` | final class | One-at-a-time sequential execution |
| `planner` | `ParallelPlanner` | final class | All-at-once parallel execution |
| `planner` | `LoopPlanner` | final class | Cyclic execution with escalation detection |
| `planner` | `SupervisorPlanner` | final class | LLM-driven dynamic agent selection |
| `planner.goap` | `GoalOrientedPlanner` | final class | Dependency-resolved planning with replanning |
| `planner.goap` | `AgentMetadata` | record | Agent input/output key declarations |
| `planner.goap` | `SearchStrategy` | interface | Strategy for dependency graph search |
| `planner.goap` | `DfsSearchStrategy` | final class | Backward-chaining DFS search |
| `planner.goap` | `AStarSearchStrategy` | final class | Forward A* search with admissible heuristic |
| `planner.goap` | `DependencyGraphSearch` | final class | Topological search and parallel level assignment |
| `planner.goap` | `GoalOrientedSearchGraph` | final class | Immutable dependency graph data structure |
| `planner.goap` | `ReplanPolicy` | sealed interface | Failure handling policy (Ignore/FailStop/Replan) |
| `planner.p2p` | `P2PPlanner` | final class | Reactive dynamic activation with refinement |
| `planner.p2p` | `AgentActivator` | final class (pkg) | Per-agent activation state tracking |

---

## 10. License

```
Copyright 2025 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
