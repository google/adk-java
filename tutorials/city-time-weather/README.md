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

This tutorial supports multiple ways to load and run the agent:

### Option 1: Using CompiledAgentLoader (Recommended)

The simplest approach - automatically discovers agents with `ROOT_AGENT` fields:

```shell
mvn exec:java -Dadk.agents.source-dir=$PWD
```

### Option 2: Using AgentStaticLoader Programmatically

For programmatic control, use the `AdkWebServer.start()` method:

```shell
mvn exec:java -Dexec.mainClass="com.google.adk.tutorials.CityTimeWeather"
```

This uses the built-in `main` method that calls:
```java
AdkWebServer.start(ROOT_AGENT);
```

## Usage

Once running, you can interact with the agent through:
- Web interface: `http://localhost:8080`
- API endpoints for city time and weather queries
- Agent name: `multi_tool_agent`

## Agent Loaders Explained

- **CompiledAgentLoader**: Scans directories for compiled classes with `ROOT_AGENT` fields
- **AgentStaticLoader**: Takes pre-created agent instances programmatically
- Choose based on whether you want automatic discovery or programmatic control

See https://google.github.io/adk-docs/get-started/quickstart/#java for more information.
