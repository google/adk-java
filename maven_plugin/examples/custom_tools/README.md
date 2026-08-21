# Custom Tools Example

This example demonstrates three ways to create and use custom tools with configuration-based agents in the Google ADK:

1.  **Class-based tools**: The ADK automatically discovers all `@Schema`-annotated methods in a class.
2.  **Function-based tools**: Manually defining `FunctionTool` instances for each tool method.
3.  **Registry-based tools**: Using a custom `ComponentRegistry` to register and alias your tools.

## Overview

The example includes:
- **`GetWeatherTool.java`**: A simple tool class with a single `@Schema`-annotated method (`getWeather`).
- **`CustomDieTool.java`**: A more complex tool class with two methods: `rollDie` and `checkPrime`.
- **`CustomDieRegistry.java`**: A custom registry that makes the `CustomDieTool` methods available with aliased names.
- **Three config-based agents**:
    - `class_weather_agent`: Demonstrates class-based tool discovery with `GetWeatherTool`.
    - `function_die_agent`: Demonstrates using direct `FunctionTool` references from `CustomDieTool`.
    - `registry_die_agent`: Demonstrates using the custom `ComponentRegistry` with `CustomDieTool`.

## Project Structure

```
├── src/main/java/com/example/
│   ├── GetWeatherTool.java        # Simple custom tool class
│   ├── CustomDieTool.java         # Custom tool class with multiple methods
│   └── CustomDieRegistry.java     # Custom registry implementation
├── config_agents/
│   ├── class_weather_agent/
│   │   └── root_agent.yaml
│   ├── function_die_agent/
│   │   └── root_agent.yaml
│   └── registry_die_agent/
│       └── root_agent.yaml
├── pom.xml                        # Maven configuration
└── README.md                      # This file
```

## How to Use

### 1. Build and Run

Compile and start the ADK web server. To use the `registry_die_agent`, you must specify the custom registry.

```bash
# Navigate to the example directory
cd maven_plugin/examples/custom_tools

# Clean, compile, and start the server
mvn clean compile google-adk:web \
  -Dagents=config_agents \
  -Dregistry=com.example.CustomDieRegistry
```

The `class_die_agent` and `function_die_agent` do not require the `-Dregistry` flag, as they discover tools automatically without a registry.

### 2. Access the Web UI

Open your browser and navigate to:
```
http://localhost:8000
```

You will see all three agents (`class_weather_agent`, `function_die_agent`, `registry_die_agent`) available in the UI.

## Key Components & Concepts

### Simple Tool Class (`GetWeatherTool.java`)

This class demonstrates the most straightforward way to create a tool. It contains a single static method `getWeather`, annotated with `@Schema` to describe its function to the LLM.

```java
public class GetWeatherTool {
  @Schema(name = "get_weather", description = "Get current weather information for a city")
  public static Map<String, Object> getWeather(
      @Schema(name = "city", description = "The city to fetch weather for.") String city) { ... }
}
```

### Multi-method Tool Class (`CustomDieTool.java`)

This class contains the core logic for multiple related tools. It has two static methods, `rollDie` and `checkPrime`, each annotated with `@Schema`. It also explicitly creates `FunctionTool` instances, which are used in Approach 2.

```java
public class CustomDieTool {
  public static final FunctionTool ROLL_DIE_INSTANCE =
      FunctionTool.create(CustomDieTool.class, "rollDie");
  public static final FunctionTool CHECK_PRIME_INSTANCE =
      FunctionTool.create(CustomDieTool.class, "checkPrime");

  @Schema(name = "roll_die", description = "Roll a die with specified number of sides")
  public static Single<Map<String, Object>> rollDie(...) { ... }

  @Schema(name = "check_prime", description = "Check if numbers are prime")
  public static Single<Map<String, Object>> checkPrime(...) { ... }
}
```

### Approach 1: Class-Based Tools (`class_weather_agent`)

This is the simplest approach. The agent configuration just needs the fully qualified class name. The ADK will automatically find all public, static, `@Schema`-annotated methods in that class and make them available as tools.

**`config_agents/class_weather_agent/root_agent.yaml`**:
```yaml
name: "class_weather_agent"
instruction: "You are a helpful weather assistant."
tools:
  - name: "com.example.GetWeatherTool"
```

### Approach 2: Function-Based Tools (`function_die_agent`)

This approach gives you more control. You explicitly point to the `FunctionTool` instances for each tool you want to include. This is useful if you only want to expose a subset of the methods in a class.

**`config_agents/function_die_agent/root_agent.yaml`**:
```yaml
tools:
 - name: com.example.CustomDieTool.ROLL_DIE_INSTANCE
 - name: com.example.CustomDieTool.CHECK_PRIME_INSTANCE
```

### Approach 3: Registry-Based Tools (`registry_die_agent`)

This approach is useful for decoupling your agent configuration from your code implementation. You can create aliases for your tools.

**`src/main/java/com/example/CustomDieRegistry.java`**:
```java
public class CustomDieRegistry extends ComponentRegistry {
    public CustomDieRegistry() {
        super();
        register("tools.roll_die", CustomDieTool.ROLL_DIE_INSTANCE);
        register("tools.check_prime", CustomDieTool.CHECK_PRIME_INSTANCE);
    }
}
```

**`config_agents/registry_die_agent/root_agent.yaml`**:
```yaml
tools:
  - name: tools.roll_die
  - name: tools.check_prime
```

## Sample Queries

Once the server is running, you can try these sample queries.

**For the `class_weather_agent`:**
- "What's the weather like in London?"
- "Is it sunny in New York?"

**For `function_die_agent` or `registry_die_agent`:**
- "Roll a 20-sided die"
- "Roll a d6 and check if the result is a prime number"
- "I rolled a 3, 5, and 7 before. Now roll a d12 and tell me which of my rolls are prime."