# Simple ADK Agent Example

This example demonstrates how to create and run custom ADK agents using the Maven plugin.

## Overview

The example includes:
- **SimpleAgentLoader**: An implementation of `AgentLoader` that creates three example agents
- **chat_assistant**: A friendly general-purpose assistant
- **search_agent**: An agent with Google Search capabilities
- **code_helper**: A coding assistant

## Project Structure

```
├── src/main/java/com/example/
│   └── SimpleAgentLoader.java         # Agent loader implementation
├── pom.xml                           # Maven configuration
└── README.md                         # This file
```

## How to Use

### 1. Build and Run

Compile and start the ADK web server:

```bash
# Navigate to the example directory
cd maven_plugin/examples/simple-agent

# Clean, compile, and start the server
mvn clean compile google-adk:web \
  -Dagents=com.example.SimpleAgentLoader
```

### 2. Access the Web UI

Open your browser and navigate to:
```
http://localhost:8000
```

You should see three agents available in the dropdown: chat_assistant, search_agent, and code_helper.

## Key Components

### SimpleAgentLoader

The `SimpleAgentLoader` implements the `AgentLoader` interface and creates multiple agents:

```java
@Override
@Nonnull
public ImmutableList<String> listAgents() {
  return ImmutableList.of("chat_assistant", "search_agent", "code_helper");
}

@Override
public BaseAgent loadAgent(String name) {
  switch (name) {
    case "chat_assistant":
      return createChatAssistant();
    case "search_agent":
      return createSearchAgent();
    case "code_helper":
      return createCodeHelper();
    default:
      throw new NoSuchElementException("Agent not found: " + name);
  }
}
```

### Agent Implementations

Each agent is created with specific instructions and capabilities:

- **chat_assistant**: General-purpose conversational agent
- **search_agent**: Includes Google Search tool for web information
- **code_helper**: Specialized for coding assistance and questions

## Sample Queries

Try these sample queries with different agents:

### Chat Assistant
- "Hello! How can you help me today?"
- "Tell me about artificial intelligence"
- "What's your favorite color?"

### Search Agent
- "What's the latest news about AI?"
- "Search for information about quantum computing"
- "Find recent developments in renewable energy"

### Code Helper
- "How do I create a REST API in Java?"
- "Explain the difference between ArrayList and LinkedList"
- "Show me how to implement a binary search algorithm"

## Extending This Example

You can extend this example by:

1. **Adding more agents**: Create additional agent types with specialized instructions
2. **Using different models**: Try different LLM models
3. **Adding custom tools**: Integrate additional tools for specific functionalities
4. **Agent hierarchies**: Implement parent-child agent relationships

## Best Practices

- Use descriptive names and instructions for agents
- Test each agent's capabilities through the web UI
- Consider the target use case when designing agent instructions
- Use appropriate models based on the agent's complexity needs