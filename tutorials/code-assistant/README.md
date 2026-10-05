# Code Assistant Agent

A tutorial demonstrating how to build an AI code assistant agent using the Google ADK (Agent Development Kit). The agent can help with code review, debugging, generating code snippets, and explaining programming concepts.

## Setup API Key

```shell
export GOOGLE_API_KEY={YOUR-KEY}
```

## Go to example directory

```shell
cd /google_adk/tutorials/code-assistant
```

## Running the Agent

Start the server:

```shell
mvn exec:java -Dadk.agents.source-dir=$PWD
```

This starts the ADK web server with a code assistant agent (`code_assistant`) that can help with various programming tasks using the `gemini-2.0-flash` model.

## Usage

Once running, you can interact with the agent through:
- **Web interface:** `http://localhost:8080`
- **Agent name:** `code_assistant`
- **Try asking:** 
  - "Write a Java function to reverse a string"
  - "Review this code and suggest improvements"
  - "Explain what this code does"
  - "Help me debug this error"

## Features

This code assistant agent includes several useful tools:

- **Code Generation**: Generate code snippets in various languages
- **Code Review**: Analyze code and suggest improvements
- **Code Explanation**: Explain what code does in simple terms
- **Debugging Help**: Assist with identifying and fixing bugs
- **Best Practices**: Provide guidance on coding best practices

## Agent Capabilities

The agent is designed to:
- Understand programming questions and requests
- Generate syntactically correct code
- Provide explanations in clear, accessible language
- Follow best practices and security guidelines
- Handle multiple programming languages (Java, Python, JavaScript, etc.)

## Learn More

See https://google.github.io/adk-docs/get-started/quickstart/#java for more information.
