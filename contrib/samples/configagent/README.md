# Config-based Agent Samples

This directory contains several samples demonstrating ADK agent configuration using YAML files:

1. **Core Basic Config** - The simplest possible agent configuration
2. **Core Callback Config** - An agent with comprehensive callback hooks for lifecycle events
3. **Core Generate Content Config** - An agent demonstrating generate_content_config settings
4. **Tool Builtin Config** - An agent with built-in Google search tool
5. **Tool Functions Config** - An agent with custom Java-based function tools
6. **Multi Agent Basic Config** - Multiple specialized agents (coding tutor, math tutor)
7. **Multi Agent LLM Config** - Multi-agent system with dice rolling and prime checking capabilities
8. **Tool MCP Stdio File System Config** - An agent with MCP filesystem tools via stdio transport
9. **Sub Agents Config** - Demonstrates programmatic sub-agent resolution using the `code` field

## Project Structure

```
├── core_basic_config/
│   └── root_agent.yaml       # Basic agent configuration
├── core_callback_config/
│   └── root_agent.yaml       # Agent with callback hooks
├── core_generate_content_config_config/
│   └── root_agent.yaml       # Agent with generate_content_config settings
├── tool_builtin_config/
│   └── root_agent.yaml       # Agent with built-in search tool
├── tool_functions_config/
│   └── root_agent.yaml       # Agent with custom function tools
├── multi_agent_basic_config/
│   ├── root_agent.yaml       # Root coordinator agent
│   ├── code_tutor_agent.yaml # Coding tutor sub-agent
│   └── math_tutor_agent.yaml # Math tutor sub-agent
├── multi_agent_llm_config/
│   ├── root_agent.yaml       # Root agent with dice/prime delegation
│   ├── roll_agent.yaml       # Dice rolling sub-agent
│   └── prime_agent.yaml      # Prime checking sub-agent
├── tool_mcp_stdio_file_system_config/
│   └── root_agent.yaml       # Agent with MCP filesystem tools
├── sub_agents_config/
│   ├── root_agent.yaml       # Root agent using programmatic sub-agents
│   ├── work_agent.yaml       # Work-related sub-agent
│   └── LifeAgent.java        # Life agent implementation (registered via code)
├── src/
│   ├── CustomDieTool.java    # Custom tool implementation
│   ├── CoreCallbacks.java    # Callback implementations
│   └── CustomDemoRegistry.java # Consolidated custom registry
├── pom.xml                   # Maven configuration
└── README.md                 # This file
```

## How to Use

### 1. Navigate to this sample directory
```bash
cd contrib/samples/configagent
```

### 2. Run the ADK web server
Run all agents simultaneously:
```bash
mvn clean compile google-adk:web -Dagents=. \
  -Dregistry=com.example.CustomDemoRegistry
```

Or run individual agents:
```bash
# Basic agent only
mvn google-adk:web -Dagents=core_basic_config

# Custom function tools agent (requires compilation and registry)
mvn clean compile google-adk:web \
  -Dagents=tool_functions_config \
  -Dregistry=com.example.CustomDemoRegistry
```

### 3. Access the Web UI
Open your browser and navigate to:
```
http://localhost:8000
```

You will see the configured agent(s) available in the UI.

## Sample 1: Core Basic Config (`core_basic_config/`)

Basic Q&A agent with essential fields: name, model, description, instruction.

**Sample queries:**
- "Hello, can you help me?"
- "Explain what machine learning is"

## Sample 2: Core Callback Config (`core_callback_config/`)

Agent demonstrating comprehensive callback hooks for monitoring and debugging agent lifecycle events. This sample showcases how callbacks can be used to track agent execution, model interactions, and tool usage.


**Sample queries:**
- "Roll a 6-sided die" - Triggers tool callbacks
- "Roll a 20-sided die and check if it's prime" - Demonstrates multiple callback types
- "Check if 7, 11, and 13 are prime numbers" - Shows batch processing with callbacks

**Callback Types:**
- **Agent callbacks**: Execute before/after the agent processes a request
- **Model callbacks**: Execute before/after LLM invocation
- **Tool callbacks**: Execute before/after tool execution

## Sample 3: Core Generate Content Config (`core_generate_content_config_config/`)

Search agent demonstrating the use of `generate_content_config` to control LLM generation parameters.

**Key Configuration:**
```yaml
generate_content_config:
  temperature: 0.1          # Lower temperature for more focused responses
  max_output_tokens: 2000   # Limit response length
```

**Sample queries that demonstrate the configuration:**
- "Generate a creative story about a robot" - Run this multiple times; with temperature 0.1, you'll get very similar stories each time (low creativity/high consistency)
- "List 3 uses for a paperclip" - Run multiple times; low temperature means you'll likely get the same common uses each time (writing, holding papers, reset button)
- "Search for machine learning frameworks and provide a comprehensive comparison" - Tests the max_output_tokens limit of 2000; response will be truncated if it exceeds this limit

## Sample 4: Tool Builtin Config (`tool_builtin_config/`)

Search agent with built-in Google search tool.

**Sample queries:**
- "Search for the latest news about artificial intelligence"
- "What are the top Python frameworks in 2024?"

## Sample 5: Tool Functions Config (`tool_functions_config/`)

Agent with custom Java-based function tools for dice rolling and prime number checking.

**Custom Tools:**
- `roll_die(sides)` - Rolls a die with specified number of sides
- `check_prime(nums)` - Checks if a list of numbers are prime

**Sample queries:**
- "Roll a 6-sided die"
- "Roll a 20-sided die and check if the result is prime"

## Sample 6: Multi Agent Basic Config (`multi_agent_basic_config/`)

Learning assistant with specialized sub-agents for coding and math tutoring. The root agent coordinates between specialized tutors.

**Sub-agents:**
- `code_tutor_agent` - Programming concepts and code debugging
- `math_tutor_agent` - Mathematical concepts and problem solving

**Sample queries:**
- "How do I write a for loop in Python?"
- "Explain what recursion is with an example"

## Sample 7: Multi Agent LLM Config (`multi_agent_llm_config/`)

Multi-agent system demonstrating delegation between specialized agents for dice rolling and prime number checking. Includes few-shot examples and safety settings configuration.

**Sub-agents:**
- `roll_agent` - Handles dice rolling with different sizes
- `prime_agent` - Checks whether numbers are prime

**Features:**
- Demonstrates multi-agent coordination and delegation
- Uses custom Java tools (roll_die, check_prime)
- Includes few-shot examples via ExampleTool
- Shows safety settings configuration
- Root agent delegates tasks to specialized sub-agents

**Sample queries:**
- "Roll a 6-sided die"
- "Roll a 20-sided die and check if the result is prime"

## Sample 8: Tool MCP Stdio File System Config (`tool_mcp_stdio_file_system_config/`)

File system assistant using Model Context Protocol (MCP) stdio transport to manage files and directories.

**Features:**
- Connects to file system via MCP stdio server
- Read, write, search, and manage files and directories
- Uses `@modelcontextprotocol/server-filesystem` npm package
- Works with `/tmp/mcp-demo` directory

**Setup:**
1. Ensure you have Node.js installed
2. Create the demo directory: `mkdir -p /tmp/mcp-demo`
3. Run the agent:
```bash
mvn google-adk:web -Dagents=tool_mcp_stdio_file_system_config -Dport=8001
```

**Sample queries:**
- "List all files in the current directory"
- "Create a new file called 'notes.txt' in /tmp/mcp-demo with some random content"

## Sample 9: Sub Agents Config (`sub_agents_config/`)

Demonstrates programmatic sub-agent resolution using the `code` field, which provides Python ADK compatibility. This sample shows how to reference agents registered in the ComponentRegistry.

**Features:**
- Root agent that routes queries to different sub-agents
- Life agent loaded programmatically via `code` field
- Work agent loaded from YAML config file
- Uses CustomDemoRegistry to register the LifeAgent

**Configuration Example:**
```yaml
sub_agents:
  - config_path: ./work_agent.yaml  # Traditional YAML file reference
  - code: sub_agents_config.life_agent.agent  # Programmatic reference via registry
```

**Setup:**
```bash
mvn clean compile google-adk:web \
  -Dagents=sub_agents_config \
  -Dregistry=com.example.CustomDemoRegistry
```

**Sample queries:**
- "What is the meaning of life?" (routed to life agent)
- "How can I be more productive at work?" (routed to work agent)
- "Tell me about the weather" (handled by root agent)
