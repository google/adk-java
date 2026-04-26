# Report Writer

This project demonstrates the **Hierarchical Decomposition** multi-agent pattern (also known as the "Russian Doll" pattern) using the ADK (Agent Development Kit) framework. In this pattern, agents are organized in a nested hierarchy where higher-level agents delegate tasks to lower-level agents, each responsible for specific subtasks.

## Overview

The Hierarchical Decomposition pattern breaks down complex tasks into smaller, manageable subtasks handled by specialized agents. This creates a layered architecture where:

- **Top-level agents** coordinate and delegate work
- **Mid-level agents** manage subteams or workflows
- **Bottom-level agents** perform specific operations

In this demo, the system generates comprehensive reports on user-specified topics by decomposing the task into research and writing phases.

## How It Works

The report writing process follows a hierarchical structure:

1. **Report Writer Agent** (Top Level): Receives the user request and orchestrates the overall process.
2. **Research Coordinator Agent** (Mid Level): Manages the research phase with two sub-agents.
3. **Topic Researcher Agent** (Bottom Level): Performs web searches to gather information.
4. **Content Analyst Agent** (Bottom Level): Analyzes and summarizes the research findings.

## Flow Diagram

```
User Input
    ↓
ReportWriterAgent (Hierarchical Decomposition)
    ↓
ResearchCoordinatorAgent (Coordinator)
    ├─→ TopicResearcherAgent (with Google Search Tool)
    └─→ ContentAnalystAgent
    ↓
Final Report Output
```

## Agents

- **ReportWriterAgent**: Main orchestrator that delegates research tasks and compiles the final report.
- **ResearchCoordinatorAgent**: Coordinates research activities, managing the TopicResearcher and ContentAnalyst agents.
- **TopicResearcherAgent**: Uses web search tools to gather relevant information on the topic.
- **ContentAnalystAgent**: Analyzes research data and creates concise summaries.

## Running the Project

1. Ensure you have Maven installed.
2. Navigate to the project directory.
3. Compile and run:

```bash
cd contrib/multi-agent-patterns
mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.report.ReportWriterApp
```

The application starts a web server where you can interact with the agents.

## Example Usage

Try prompts like:
- "Write a report on artificial intelligence"
- "Create a report on self-driving vehicles"

## Configuration

Agent configurations are defined in YAML files under `src/main/resources/agents/report/`:
- `root-writer.yaml`: Report Writer Agent configuration
- `research-coordinator.yaml`: Research Coordinator Agent configuration
- `topic-researcher.yaml`: Topic Researcher Agent configuration
- `content-analyst.yaml`: Content Analyst Agent configuration

## Dependencies

- ADK Core
- ADK Dev (for web UI)
- Search tools for web research