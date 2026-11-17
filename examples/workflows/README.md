# Workflow Samples

These samples build workflow graphs with the ADK workflow API. Each sample sets
its workflow as an app's root node and runs it with `Runner`. Most of them port
an adk-python workflow sample.

| Sample | What it shows | Calls Gemini |
| --- | --- | --- |
| `ClassifierNodeWorkflow` | A classifier node routes a support ticket to the team that handles it, and a second classifier node decides whether a technical ticket is urgent. | Yes |
| `DynamicFanOutFanInWorkflow` | A node runs a headline agent on each of the user's topics concurrently with `runNode` and tabulates the headlines. | Yes |
| `DynamicNodesWorkflow` | A node runs a headline agent and a grader agent with `runNode` in a loop until the grader accepts the headline. | Yes |
| `FanOutFanInWorkflow` | Three nodes transform the user's text concurrently, and a join combines their outputs. | No |
| `LoopSelfWorkflow` | A node routes back to itself until it guesses the user's number. | No |
| `LoopWorkflow` | An agent writes a headline, a grader agent with an output schema judges it, and a node routes on the grader's output back to the writer until it passes. | Yes |
| `MessageTriageWorkflow` | An agent labels a message, and a node emits a route per label so several handlers run. | Yes |
| `MessageWorkflow` | Nodes send text, an inline image, several messages, and a streamed sentence. | No |
| `MultiTriggersWorkflow` | Three nodes transform the user's text concurrently, and each one triggers its own run of the same node. | No |
| `NestedWorkflow` | A nested workflow of two agents runs beside a third agent, and a join hands both branches' answers to a node that combines them. | Yes |
| `NodeOutputWorkflow` | Nodes output a string and an event, an agent with an output schema outputs structured data, and the last node reads it as a record. | Yes |
| `OrderProcessingWorkflow` | Nested workflows and a tool check an order concurrently, then a node routes it based on an agent's risk decision: charge, review, or freeze as fraud. | Yes |
| `ParallelWorkerWorkflow` | An agent lists related topics, a function and an agent process each topic as parallel workers, and a node joins the explanations. | Yes |
| `RequestInputAdvancedWorkflow` | A node asks a manager to decide, sending a payload and asking for an answer that matches a schema. | Yes |
| `RequestInputRerunWorkflow` | The `RequestInputWorkflow` review in one node, which reruns when the human answers. | Yes |
| `RequestInputWorkflow` | A node pauses the workflow until a human approves, rejects, or asks to revise a draft. | Yes |
| `ResearchWorkflow` | A planner fans out to two agents that search the web, and a reviewer sends the writer's brief back until it is complete. | Yes |
| `RetryWorkflow` | A retry policy reruns a failing node before the next node reports its result. | No |
| `ReturnsWorkflow` | Function tools run as graph steps, and a routing map matches groups of statuses with `anyOf`. | No |
| `RouteWorkflow` | An agent classifies the input, and a node routes it to the agent or node that handles it. | Yes |
| `SequenceWorkflow` | One agent names a fruit, and the next agent receives that answer as its input and tells a health benefit of it. | Yes |
| `StateWorkflow` | Nodes write session state through the context and through an event, then read it back. | No |
| `SupportTriageWorkflow` | An agent classifies a support request, a routing map sends it to a specialist agent that uses a function tool, and a summarizer agent writes the reply. | Yes |
| `UseAsOutputWorkflow` | A node runs a summarizer agent with `runNode` and `useAsOutput`, so the summary becomes the node's output for the next node. | Yes |

## Project Layout

```
├── *Workflow.java  // One sample per file: its graph, nodes, agents and main
├── pom.xml         // Maven configuration and default exec main class
└── README.md       // This file
```

## Prerequisites

-   Java 17+
-   Maven 3.9+
-   For the samples that call Gemini, credentials for the Gemini API
    (`GOOGLE_API_KEY`) or Vertex AI (`GOOGLE_GENAI_USE_VERTEXAI=true`,
    `GOOGLE_CLOUD_PROJECT` and `GOOGLE_CLOUD_LOCATION`)

## Build and Run

`mvn clean compile exec:java` runs `FanOutFanInWorkflow`. To run another
sample, pass its main class:

```bash
mvn compile exec:java -Dexec.mainClass=com.example.workflows.RouteWorkflow
```

The samples that call Gemini use `gemini-3.8-flash`. To try another model,
pass its name:

```bash
mvn compile exec:java -Dexec.mainClass=com.example.workflows.RouteWorkflow \
    -Dexec.args="<model name>"
```
