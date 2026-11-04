# Workflow Samples

These samples build workflow graphs with the ADK workflow API. Each sample sets
its workflow as an app's root node and runs it with `Runner`. Most of them port
an adk-python workflow sample.

| Sample | What it shows | Calls Gemini |
| --- | --- | --- |
| `FanOutFanInWorkflow` | Three nodes transform the user's text concurrently, and a join combines their outputs. | No |
| `LoopSelfWorkflow` | A node routes back to itself until it guesses the user's number. | No |
| `MessageWorkflow` | Nodes send text, an inline image, several messages, and a streamed sentence. | No |
| `MultiTriggersWorkflow` | Three nodes transform the user's text concurrently, and each one triggers its own run of the same node. | No |
| `StateWorkflow` | Nodes write session state through the context and through an event, then read it back. | No |

## Project Layout

```
├── *Workflow.java  // One sample per file: its graph, nodes and main
├── pom.xml         // Maven configuration and default exec main class
└── README.md       // This file
```

## Prerequisites

-   Java 17+
-   Maven 3.9+

## Build and Run

`mvn clean compile exec:java` runs `FanOutFanInWorkflow`. To run another
sample, pass its main class:

```bash
mvn compile exec:java -Dexec.mainClass=com.example.workflows.LoopSelfWorkflow
```
