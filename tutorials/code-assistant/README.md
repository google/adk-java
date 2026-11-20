# Code Assistant Agent

A tutorial demonstrating how to build a multi-agent AI code assistant using the Google ADK (Agent Development Kit). A coordinator agent delegates to specialized sub-agents that write, review, and debug code.

## Setup API Key

```shell
export GOOGLE_API_KEY={YOUR-KEY}
```

## Go to example directory

```shell
cd tutorials/code-assistant
```

## Running the Agent

Start the server:

```shell
mvn exec:java
```

This starts the ADK web server with the `code_assistant` agent, which uses the `gemini-3.8-flash` model.

## Architecture

`code_assistant` is a coordinator that routes each request to one of three sub-agents via ADK agent transfer:

- **`code_writer`** — writes and explains code in Java, Python, and JavaScript. Verifies Java snippets by compiling them before answering.
- **`code_reviewer`** — reviews code for correctness, style, and best practices, grounding findings in real compiler diagnostics for Java.
- **`debugger`** — diagnoses compile errors, exceptions, and logic bugs, reproducing Java problems with the compiler when a snippet is provided.

Writing, reviewing, and explaining are tasks the model does itself, so the sub-agents have no special tools for them — the tutorial demonstrates `subAgents(...)` delegation instead.

## The `compileJava` Function Tool

Sub-agents share one function tool for work the model cannot do on its own: `compileJava` compiles a Java snippet with the JDK's `javax.tools.JavaCompiler` and returns the actual diagnostics (kind, line, column, message). This lets the writer verify code compiles, the reviewer cite real compiler errors, and the debugger reproduce failures.

`compileJava` requires running on a JDK (not a JRE). For non-Java input it returns `status: "unsupported"`, and for empty input it returns `status: "error"`.

## Usage

Once running, you can interact with the agent through:

- **Web interface:** `http://localhost:8080`
- **Agent name:** `code_assistant`
- **Try asking:**
  - "Write a Java function to reverse a string"
  - "Review this code and suggest improvements"
  - "Explain what this code does"
  - "Help me debug this compile error"

## Learn More

See https://google.github.io/adk-docs/get-started/quickstart/#java for more information.
