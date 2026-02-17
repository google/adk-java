# Gemini Code Assistant Context

This document provides context for the Gemini Code Assistant to understand the "Agent Development Kit (ADK) for Java" project.

## Project Overview

The "Agent Development Kit (ADK) for Java" is an open-source, code-first toolkit for building, evaluating, and deploying sophisticated AI agents with flexibility and control. It allows developers to define agent behavior, orchestration, and tool use directly in Java code, enabling robust debugging, versioning, and deployment.

The project is a multi-module Maven project with the following key modules:

*   **core**: The core module contains the main logic of the ADK. It includes the following key components:
    *   **`BaseAgent`**: The abstract base class for all agents. It defines the basic properties of an agent, such as its name, description, and sub-agents. It also defines the `runAsync()` and `runLive()` methods, which are the entry points for running an agent.
    *   **`LlmAgent`**: The primary implementation of a `BaseAgent`. It can be configured with a model, instructions, tools, and sub-agents.
    *   **`BaseSessionService`**: An interface for managing sessions. It provides methods for creating, retrieving, listing, and deleting sessions, as well as listing and appending events to a session.
    *   **`Runner`**: The main class for running agents. It takes a `BaseAgent`, a `BaseArtifactService`, a `BaseSessionService`, and a `BaseMemoryService` as input. It provides `runAsync()` and `runLive()` methods for running the agent.
    *   **Core Runtime**: The core runtime of the ADK is responsible for orchestrating the execution of agents. It uses the `Runner` to run the agent and the `BaseSessionService` to manage the session.
*   **dev**: This module contains the development UI for testing, evaluating, and debugging agents. It includes a Spring Boot-based web server that can be used to debug agents developed using ADK.

    **Agent Loading:**

    The `dev` module provides a flexible mechanism for loading agents into the ADK Web Server through the `AgentLoader` interface. This interface has two implementations:

    *   **`AgentStaticLoader`**: This loader takes a static list of pre-created agent instances. It's ideal for production environments or when you have a fixed set of agents.
    *   **`CompiledAgentLoader`**: This loader scans a directory for pre-compiled agent classes. It identifies agents by looking for a `public static` field named `ROOT_AGENT` of type `BaseAgent`. This is useful for development environments where you want to automatically discover and load agents.
*   **maven_plugin**: This module provides a Maven plugin for the ADK. The plugin provides the `google-adk:web` goal, which starts the ADK Web Server with user-provided agents. The plugin can be configured with the following parameters:

    *   `agents`: The fully qualified class name of an `AgentLoader` implementation or a path to a directory containing agent configurations.
    *   `port`: The port number for the web server.
    *   `host`: The host address to bind the web server to.
    *   `hotReloading`: Whether to enable hot reloading of agent configurations.
    *   `registry`: The fully qualified class name of a custom `ComponentRegistry` subclass.

    The `maven_plugin` module also includes the `ConfigAgentLoader` class, which is an implementation of the `AgentLoader` interface that loads agents from YAML configuration files. It scans a source directory for subdirectories containing a `root_agent.yaml` file. Each subdirectory is treated as an agent, and the folder name is used as the agent identifier. The `ConfigAgentLoader` also supports hot-reloading.


## Building and Running

The project is built using Maven. Use the `./mvnw` wrapper script for all commands.

### Maven Commands

*   **Build the entire project**:

    ```shell
    ./mvnw clean install
    ```

*   **Run tests**:

    ```shell
    ./mvnw test
    ```

*   **Format code**:

    ```shell
    ./mvnw fmt:format
    ```

*   **Skip tests during build**:

    ```shell
    ./mvnw clean install -DskipTests
    ```

## Development Workflow

### Running the Development UI

The development UI provides an interactive chat interface for testing and debugging agents. It can be started using the Maven plugin:

*   **Using an `AgentLoader` class**:

    ```shell
    mvn google-adk:web -Dagents=com.example.MyAgentLoader.INSTANCE -Dport=8000
    ```

*   **Using a config directory (YAML-based agents)**:

    ```shell
    mvn google-adk:web -Dagents=path/to/config/dir
    ```

*   **With hot reloading disabled**:

    ```shell
    mvn google-adk:web -Dagents=... -DhotReloading=false
    ```

Once started, the dev UI is available at `http://localhost:8000` (or the specified port).

## Development Conventions

*   **Coding Style**: The project follows the Google Java Style Guide. The `fmt-maven-plugin` is used to format the code automatically.
    *   **Import Style**: Always use import statements instead of fully qualified class names in code. Prefer `import com.google.adk.agents.InvocationContext;` over using `com.google.adk.agents.InvocationContext` directly in the code.
*   **Testing**: The project uses JUnit 5 for testing. Tests are located in the `src/test/java` directory of each module.
*   **Contributing**: Contributions are welcome. Please see the `CONTRIBUTING.md` file for more information.
