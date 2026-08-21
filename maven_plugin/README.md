# Google ADK Maven Plugin

[![Maven Central](https://img.shields.io/maven-central/v/com.google.adk/google-adk-maven-plugin)](https://search.maven.org/artifact/com.google.adk/google-adk-maven-plugin)

This Maven plugin provides a convenient way to start the ADK Web Server with your custom agents for development and testing.

## Usage

### Basic Usage

```bash
mvn google-adk:web -Dagents=com.example.MyAgentLoader.INSTANCE
```

### Parameters

- **`agents`** (required): Full class path to your AgentLoader implementation OR path to agent configuration directory
- **`port`** (optional, default: 8000): Port for the web server
- **`host`** (optional, default: localhost): Host address to bind to
- **`hotReloading`** (optional, default: true): Whether to enable hot reloading

### Example with Custom Parameters

```bash
mvn google-adk:web \
  -Dagents=com.example.MyAgentLoader.INSTANCE \
  -Dhost=0.0.0.0 \
  -Dport=9090 \
  -DhotReloading=false
```

## Creating an AgentLoader

### 1. Implement the AgentLoader Interface

Create a class that implements `com.google.adk.web.AgentLoader`:

```java
package com.example;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.web.AgentLoader;
import com.google.common.collect.ImmutableList;
import java.util.NoSuchElementException;

public class MyAgentLoader implements AgentLoader {

    public static final MyAgentLoader INSTANCE = new MyAgentLoader();

    @Override
    public ImmutableList<String> listAgents() {
        return ImmutableList.of("chat_bot", "code_assistant");
    }

    @Override
    public BaseAgent loadAgent(String name) {
        switch (name) {
            case "chat_bot": return createChatBot();
            case "code_assistant": return createCodeAssistant();
            default: throw new NoSuchElementException("Agent not found: " + name);
        }
    }

    private BaseAgent createChatBot() {
        return LlmAgent.builder()
            .name("chat_bot")
            .description("A helpful chat bot")
            .model("gemini-2.0-flash")
            .instruction("You are a helpful assistant.")
            .build();
    }

    private BaseAgent createCodeAssistant() {
        return LlmAgent.builder()
            .name("code_assistant")
            .description("A code assistance agent")
            .model("gemini-2.0-flash")
            .instruction("You are a coding assistant. Help users with programming questions.")
            .build();
    }
}
```

### 2. Add the Plugin to Your Project

Add the plugin to your `pom.xml`:

<!-- {x-version-start:google-adk:released} -->
```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk-maven-plugin</artifactId>
            <version>0.3.0</version>
        </plugin>
    </plugins>
</build>
```
<!-- {x-version-end} -->

### 3. Run the Web Server

```bash
mvn google-adk:web -Dagents=com.example.MyAgentLoader.INSTANCE
```

## Alternative AgentLoader Patterns

### Using Default Constructor

```java
public class SimpleAgentLoader implements AgentLoader {
    // No static field needed, uses default constructor
    @Override
    public ImmutableList<String> listAgents() {
        return ImmutableList.of("simple_agent");
    }

    @Override
    public BaseAgent loadAgent(String name) {
        if ("simple_agent".equals(name)) {
            return createSimpleAgent();
        }
        throw new NoSuchElementException("Agent not found: " + name);
    }

    private BaseAgent createSimpleAgent() {
        return LlmAgent.builder()
            .name("simple_agent")
            .description("A simple agent")
            .model("gemini-2.0-flash")
            .instruction("You are a helpful assistant.")
            .build();
    }
}
```

Usage:

```bash
mvn google-adk:web -Dagents=com.example.SimpleAgentLoader
```

### Using Other Static Fields

```java
public class MultipleLoaders implements AgentLoader {
    public static final MultipleLoaders DEFAULT = new MultipleLoaders();
    public static final MultipleLoaders ADVANCED = new MultipleLoaders(true);

    private final boolean advanced;

    public MultipleLoaders() { this(false); }
    public MultipleLoaders(boolean advanced) { this.advanced = advanced; }

    @Override
    public ImmutableList<String> listAgents() {
        if (advanced) {
            return ImmutableList.of("advanced_agent");
        }
        return ImmutableList.of("basic_agent");
    }

    @Override
    public BaseAgent loadAgent(String name) {
        if (advanced && "advanced_agent".equals(name)) {
            return createAdvancedAgent();
        } else if (!advanced && "basic_agent".equals(name)) {
            return createBasicAgent();
        }
        throw new NoSuchElementException("Agent not found: " + name);
    }

    private BaseAgent createBasicAgent() {
        return LlmAgent.builder()
            .name("basic_agent")
            .description("A basic agent")
            .model("gemini-2.0-flash")
            .instruction("You are a basic helpful assistant.")
            .build();
    }

    private BaseAgent createAdvancedAgent() {
        return LlmAgent.builder()
            .name("advanced_agent")
            .description("An advanced agent with more capabilities")
            .model("gemini-2.0-flash")
            .instruction("You are an advanced assistant with enhanced capabilities.")
            .build();
    }
}
```

Usage:

```bash
mvn google-adk:web -Dagents=com.example.MultipleLoaders.ADVANCED
```

## Config-Based Agents (Directory Path)

For configuration-based agents using YAML files, you can provide a directory path instead of a class name. The plugin will automatically use `ConfigAgentLoader` to scan for agent directories.

### Directory Structure

Create a parent directory containing subdirectories, each representing an agent with a `root_agent.yaml` file:

```
my-agents/
├── chat-assistant/
│   └── root_agent.yaml
├── search-agent/
│   └── root_agent.yaml
└── code-helper/
    ├── root_agent.yaml
    └── another_agent.yaml
```

### Usage with Directory Path

```bash
mvn google-adk:web -Dagents=my-agents
```

Or with absolute path:

```bash
mvn google-adk:web -Dagents=/home/user/my-agents
```

### Hot Reloading for Config Agents

When using config-based agents, hot reloading is enabled by default. The plugin will automatically detect changes to any YAML files within the agent directories and reload agents without restarting the server.

To disable hot reloading:

```bash
mvn google-adk:web -Dagents=my-agents -DhotReloading=false
```

### Example root_agent.yaml

```yaml
name: "chat_assistant"
description: "A friendly chat assistant"
model: "gemini-2.0-flash"
instruction: |
  You are a helpful and friendly assistant.
  Answer questions clearly and concisely.
  Be encouraging and positive in your responses.
```

## Web UI

Once the server starts, open your browser to:
- Default: http://localhost:8000
- Custom port: http://localhost:{port}
- External access: http://0.0.0.0:{port} (if using -Dhost=0.0.0.0)

The web UI provides:
- Interactive chat interface with your agents
- Agent selection dropdown
- Real-time streaming responses
- Session management
- Debug information

## Dependencies

Make sure your project has the necessary ADK dependencies:

<!-- {x-version-start:google-adk:released} -->
```xml
<dependencies>
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk</artifactId>
        <version>0.3.0</version>
    </dependency>

    <!-- Dev module dependency for AgentLoader interface -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk-dev</artifactId>
        <version>0.3.0</version>
        <scope>compile</scope>
    </dependency>
</dependencies>
```
<!-- {x-version-end} -->

### Plugin Usage Options

**Option 1: With plugin declaration (shorter commands)**

Add the plugin to your `pom.xml` for convenience:

<!-- {x-version-start:google-adk:released} -->
```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk-maven-plugin</artifactId>
            <version>0.3.0</version>
        </plugin>
    </plugins>
</build>
```
<!-- {x-version-end} -->

Then use the short command:

```bash
mvn google-adk:web -Dagents=com.example.MyAgentLoader.INSTANCE
```

**Option 2: Without plugin declaration (no pom.xml changes)**

Use the full coordinates directly:

```bash
mvn com.google.adk:google-adk-maven-plugin:web -Dagents=com.example.MyAgentLoader.INSTANCE
```

## Stopping the Server

Press `Ctrl+C` in the terminal to stop the server.

## Version Notes

Replace version numbers with the latest version available on [Maven Central](https://search.maven.org/artifact/com.google.adk/google-adk-maven-plugin).
