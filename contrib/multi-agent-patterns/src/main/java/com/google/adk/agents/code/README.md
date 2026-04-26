# Code Workflow

This project demonstrates various multi-agent patterns using the ADK (Agent Development Kit) framework. It showcases how multiple AI agents can collaborate to perform complex tasks such as code generation, conversion, and refinement.

## Overview

The system implements a code workflow where agents handle different aspects of software development tasks. The main patterns demonstrated are:

- **Sequential Pipeline**: Agents execute in a linear sequence, passing outputs from one to the next.
- **Coordinator/Dispatcher Pattern**: A central agent routes tasks to specialized sub-agents based on the request type.
- **Iterative Refinement Loop**: An agent iteratively improves code based on feedback until a satisfactory result is achieved.
- **Generator and Critic**: A generator creates initial content, and a critic reviews and provides feedback for improvement.

## How It Works

The multi-agent system operates through a series of coordinated workflows, each leveraging specific patterns to achieve high-quality code outputs.

### 1. Request Routing (Coordinator/Dispatcher Pattern)
- The **CodeRootAgent** analyzes the user's request.
- Based on the task type (generation, conversion, or both), it dispatches to the appropriate workflow.

### 2. Code Generation Workflow (Sequential Pipeline + Iterative Refinement Loop)
- **Generator Agent**: Produces initial code from problem statements.
- **Iterative Refinement Loop** (max 3 iterations):
  - **Review Agent**: Evaluates code for correctness, efficiency, and best practices.
  - **Refiner Agent**: Applies improvements based on review feedback.
  - Loop continues until code is approved or max iterations reached.

### 3. Code Conversion Workflow (Sequential Pipeline + Generator and Critic)
- **Converter Agent**: Translates code between programming languages.
- **Critic Agent**: Reviews converted code for accuracy and style.
- **Refactor Agent**: Refines the converted code based on critic feedback.

### 4. Full Loop Workflow (Sequential Pipeline)
- Combines generation and conversion workflows.
- **Bundler Agent**: Synthesizes all outputs into a unified, formatted report.

## Flow Diagram

```
User Input
    ↓
CodeRootAgent (Coordinator/Dispatcher)
    ↓
{Request Type}
├─→ CodeGenerationWorkflow (Sequential Pipeline)
│   ├─→ Generator Agent
│   └─→ Refinement Loop (Iterative Refinement Loop)
│       ├─→ Review Agent
│       └─→ Refiner Agent
├─→ CodeConversionWorkflow (Sequential Pipeline)
│   ├─→ Converter Agent
│   ├─→ Critic Agent (Generator and Critic)
│   └─→ Refactor Agent
└─→ FullLoopWorkflow (Sequential Pipeline)
    ├─→ CodeGenerationWorkflow
    │   ├─→ Generator Agent
    │   └─→ Refinement Loop
    │       ├─→ Review Agent
    │       └─→ Refiner Agent
    ├─→ CodeConversionWorkflow
    │   ├─→ Converter Agent
    │   ├─→ Critic Agent
    │   └─→ Refactor Agent
    └─→ Bundler Agent
    ↓
Code Output
```

## Agents

- **CodeRootAgent**: Central dispatcher
- **CodeGeneratorAgent**: Generates code from problem statements
- **CodeReviewerAgent**: Reviews generated code
- **CodeRefinerAgent**: Refines code based on feedback
- **CodeConverterAgent**: Translates code between languages
- **CodeCriticAgent**: Reviews converted code
- **CodeBundlerAgent**: Combines outputs into a report

## Running the Project

1. Ensure you have Maven installed.
2. Navigate to the project directory.
3. Compile and run:

```bash
cd contrib/multi-agent-patterns
mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.code.CodeWorkflowApp
```

The application starts a web server where you can interact with the agents.

## Dependencies

- ADK Core
- ADK Dev (for web UI)
- Jackson (for YAML/JSON handling)
- Jakarta Inject
- Apache Commons Lang

## Configuration

Agent configurations are defined in YAML files under `src/main/resources/agents/code/`:
- `root-agent.yaml`
- `generator-agent.yaml`
- `review-agent.yaml`
- `refiner-agent.yaml`
- `converter-agent.yaml`
- `critic-agent.yaml`
- `bundler-agent.yaml`