# Trip Advisor

This project demonstrates **Composite Patterns** (mix-and-match of sequential and parallel agents) using the ADK (Agent Development Kit) framework. Composite patterns combine different agent orchestration strategies—such as sequential pipelines and parallel execution—to create flexible, efficient workflows that handle complex tasks by breaking them into interdependent subtasks.

## Overview

Composite patterns allow agents to work in hybrid modes where some tasks run sequentially (one after another) while others execute in parallel (simultaneously). This approach optimizes performance by running independent tasks concurrently and ensuring dependent tasks are handled in order.

In this demo, the system plans personalized travel itineraries by combining parallel searches for travel components with sequential processing for itinerary creation.

## How It Works

The travel planning process uses a composite structure:

1. **Traveler Root Agent** (Coordinator): Receives user requests and delegates to the Trip Assistant Workflow.
2. **Trip Assistant Workflow** (Sequential): First runs the Parallel Search Team, then the Itinerary Agent.
3. **Parallel Search Team** (Parallel): Searches for flights, accommodations, and experiences concurrently.
   - **Experiences** (Sequential): Within the parallel team, activities and dining are handled sequentially.

## Flow Diagram

```
User Input
    ↓
TravelerRootAgent (Composite Patterns)
    ↓
TripAssistantWorkflow (Sequential)
    ├─→ ParallelSearchTeam (Parallel)
    │   ├─→ FlightAgent (with Search Tool)
    │   ├─→ Experiences (Sequential)
    │   │   ├─→ ActivityAgent (with Search & Maps Tools)
    │   │   └─→ DiningAgent (with Search & Maps Tools)
    │   └─→ AccommodationAgent (with Maps Tool)
    └─→ ItineraryAgent
    ↓
Travel Itinerary Output
```

## Agents

- **TravelerRootAgent**: Main coordinator that gathers requirements and delegates planning.
- **FlightAgent**: Searches for flight options using web search tools.
- **AccommodationAgent**: Finds lodging options using maps tools.
- **ActivityAgent**: Discovers local activities and attractions.
- **DiningAgent**: Recommends restaurants and dining experiences.
- **ItineraryAgent**: Compiles all search results into a cohesive travel itinerary.

## Running the Project

1. Ensure you have Maven installed.
2. Navigate to the project directory.
3. Compile and run:

```bash
cd contrib/multi-agent-patterns
mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.traveler.TravelerApp
```

The application starts a web server where you can interact with the agents.

## Example Usage

Try prompts like:
- "Plan a 5-day trip to Rome in July with a budget of $3000 for two people"

## Configuration

Agent configurations are defined in YAML files under `src/main/resources/agents/traveler/`:
- `root-agent.yaml`: Traveler Root Agent configuration
- `flight-agent.yaml`: Flight search agent
- `accommodation-agent.yaml`: Accommodation search agent
- `activity-agent.yaml`: Activity recommendations agent
- `dining-agent.yaml`: Dining recommendations agent
- `itinerary-agent.yaml`: Itinerary compilation agent

## Dependencies

- ADK Core
- ADK Dev (for web UI)
- Search and Maps tools for travel research
