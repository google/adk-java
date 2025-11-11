# City Time Weather

A tutorial demonstrating how to build an AI agent that provides current time and weather information for cities using the Google ADK (Agent Development Kit). The agent can answer questions like "What time is it in New York?"

## Setup API Key

```shell
GEMINI_API_KEY={YOUR-KEY}
```

## Go to example directory

```shell
cd /google_adk/tutorials/city-time-weather
```

## Running the Agent

This tutorial demonstrates two different agent loading approaches:

### Option 1: Using CompiledAgentLoader (Automatic Discovery)

Automatically discovers agents with `ROOT_AGENT` fields in compiled classes:

```shell
mvn exec:java -Dadk.agents.source-dir=$PWD
```

This approach:
- Uses Spring Boot to start `AdkWebServer` directly
- `CompiledAgentLoader` scans `target/classes` for `ROOT_AGENT` fields
- Automatically loads the `CityTimeWeather.ROOT_AGENT`

### Option 2: Using AgentStaticLoader (Programmatic)

Explicitly provides pre-created agent instances:

With custom port:
```shell
mvn exec:java -Dexec.mainClass="com.google.adk.tutorials.CityTimeWeather" -Dserver.port=8081
```

This approach:
- Calls `CityTimeWeather.main()` which executes `AdkWebServer.start(ROOT_AGENT)`
- Directly provides the agent instance programmatically
- Uses `AgentStaticLoader` with the provided agent

## Usage

Once running, you can interact with the agent through:
- Web interface: `http://localhost:8080`
- API endpoints for city time and weather queries
- Agent name: `multi_tool_agent`

## Agent Loading Approaches

This tutorial demonstrates both agent loading strategies:

- **CompiledAgentLoader (Option 1)**: Automatically discovers agents in compiled classes. Good for development and when you have multiple agents.
- **AgentStaticLoader (Option 2)**: Takes pre-created agent instances programmatically. Good for production and when you need precise control.

Choose Option 1 for automatic discovery, Option 2 for programmatic control.

See https://google.github.io/adk-docs/get-started/quickstart/#java for more information.
