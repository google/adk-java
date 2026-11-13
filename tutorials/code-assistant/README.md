# Code Assistant Agent

A tutorial demonstrating how to build a multi-agent AI code assistant using the Google ADK (Agent Development Kit). A coordinator agent calls specialist agents as tools to write, review, and debug code.

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

`code_assistant` is a coordinator that answers programming questions itself and delegates concrete tasks to three specialist agents wired as `AgentTool`s, so it can call them like functions and combine their results into one reply:

- **`code_writer`** — writes, explains, and refactors code. Verifies Java snippets by compiling them before answering.
- **`code_reviewer`** — reviews code for correctness, style, and best practices, grounding findings in real compiler diagnostics for Java.
- **`debugger`** — diagnoses compile errors, exceptions, and logic bugs, reproducing Java problems with the compiler and verifying proposed fixes.

Because each specialist runs as a tool call with a self-contained request, the coordinator includes the complete code and any error messages in every request. Writing, reviewing, and explaining are tasks the model does itself, so the specialists need no tools for them — the tutorial demonstrates `AgentTool` delegation instead.

## The `compileJava` Function Tool

The specialists share one function tool for work the model cannot do on its own: `compileJava` takes a complete Java source file and a `className` (used as the file name), compiles it with the JDK's `javax.tools.JavaCompiler`, and returns the status, the `fileName`, and the actual diagnostics (kind, line, column, message). This lets the writer verify code compiles, the reviewer cite real compiler errors, and the debugger reproduce failures and confirm fixes.

`compileJava` requires running on a JDK (not a JRE) and always targets `--release 17`. It returns `status: "error"` for empty code or an invalid `className`.

## Usage

Once running, you can interact with the agent through:

- **Web interface:** `http://localhost:8080`
- **Agent name:** `code_assistant`
- **Try asking:**
  - "Write a Java method that checks whether a string is a palindrome"
  - "Review this code: `boolean eq(String a, String b) { return a == b; }` — is comparing strings with `==` safe?"
  - "Explain what this code does: `while (true) { work(); }`"
  - "Help me debug this: `public class Main { public static void main(String[] a) { int x = ; } }` fails to compile"

## Learn More

See https://google.github.io/adk-docs/get-started/quickstart/#java for more information.
