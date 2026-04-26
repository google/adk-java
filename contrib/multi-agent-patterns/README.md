# Multi-Agent Patterns

This repository demonstrates various multi-agent patterns using the ADK (Agent Development Kit) framework. It showcases how multiple AI agents can collaborate to perform complex tasks across different domains, including code generation, report writing, and travel planning.

## Overview

The project implements several key multi-agent patterns:

- **Sequential Pipeline**: Agents execute in a linear sequence, passing outputs from one to the next.
- **Coordinator/Dispatcher Pattern**: A central agent routes tasks to specialized sub-agents based on the request type.
- **Iterative Refinement Loop**: An agent iteratively improves content based on feedback until a satisfactory result is achieved.
- **Generator and Critic**: A generator creates initial content, and a critic reviews and provides feedback for improvement.
- **Hierarchical Decomposition (Russian Doll)**: Agents are organized in nested layers, with higher-level agents delegating to lower-level specialized agents.
- **Composite Patterns (Mix-and-Match)**: Combines sequential and parallel execution modes for flexible, efficient workflows.

## System Architecture

All patterns are hosted within a unified system accessible through a single web interface.

```text
       [ Web Browser / UI ]
                │
     [ MultiAgentsSystem ] ◄── Entry Point
                │
      ┌─────────┴─────────┐
      ▼                   ▼
 [ Dropdown ] ───► [ Select Pattern ] ───► [ Run Workflow ]
```

## Projects

The repository includes three main projects, each illustrating different patterns:

### 1. Code Workflow (`src/main/java/com/google/adk/agents/code/`)
Demonstrates Sequential Pipeline, Coordinator/Dispatcher, Iterative Refinement Loop, and Generator and Critic patterns for code generation, conversion, and refinement.

### 2. Report Writer (`src/main/java/com/google/adk/agents/report/`)
Showcases Hierarchical Decomposition pattern for generating comprehensive reports through nested agent coordination.

### 3. Trip Advisor (`src/main/java/com/google/adk/agents/traveler/`)
Illustrates Composite Patterns with a mix of sequential and parallel agents for personalized travel itinerary planning.

## Prerequisites

- Java 17+
- Maven 3+
- **GEMINI API Key**: All agents require a valid Gemini API key for LLM operations. 
  Ensure it's configured in your environment.

## Running the Project

To run the multi-agent system:

```bash
cd contrib/multi-agent-patterns
mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.MultiAgentsSystem
```

The application starts a web server where you can interact with the respective agents.

## Project Structure

```
multiagent-patterns/
├── pom.xml
├── src/main/java/com/google/adk/agents/
│   ├── MultiAgentsSystem.java          # Unified entry point for all agents
│   ├── code/                           # Code workflow logic
│   │   ├── CodeRootAgent.java
│   │   └── ...
│   ├── report/                         # Report writer logic
│   │   ├── ReportRootAgent.java
│   │   └── ...
│   └── traveler/                       # Trip Advisor logic
│       ├── TravelerRootAgent.java
│       └── ...
└── src/main/resources/agent-configs/
    ├── code/                           # Code agent configs
    ├── report/                         # Report agent configs
    └── traveler/                       # TripAdvisor agent configs
```

## Dependencies

- ADK Core
- ADK Dev (for web UI)
- Jackson (for YAML/JSON handling)
- Jakarta Inject
- Apache Commons Lang

## Reference

For more information on multi-agent patterns in ADK, see:  
[Developer’s guide to multi-agent patterns in ADK](https://developers.googleblog.com/developers-guide-to-multi-agent-patterns-in-adk/)
