# MCP Filesystem Agent Sample

This sample mirrors the `tool_mcp_stdio_file_system_config/root_agent.yaml` configuration by wiring
an MCP filesystem toolset programmatically. The agent launches the filesystem stdio server via
`npx @modelcontextprotocol/server-filesystem` and interacts with it using the Google ADK runtime.

## Project Layout

```
├── McpFilesystemAgent.java  // Agent definition and MCP toolset wiring
├── McpFilesystemRun.java    // Console runner entry point
├── pom.xml                  // Maven configuration and exec main class
└── README.md                // This file
```

## Prerequisites

- Java 17+
- Maven 3.9+
- Node.js 18+ with `npx` available (for `@modelcontextprotocol/server-filesystem`)

`npx` downloads the MCP filesystem server on first run. Subsequent executions reuse the cached
package, so expect a longer startup time the first time you run the sample.

## Build and Run

Set the Gemini environment variables and launch the interactive session from this directory. One
command compiles and runs the sample:

```bash
export GOOGLE_GENAI_USE_VERTEXAI=FALSE
export GOOGLE_API_KEY=your_api_key
mvn clean compile exec:java
```

The runner sends an initial prompt asking the agent to list files. To explore additional operations,
reuse the same environment and pass the `--run-extended` argument (you can keep `clean compile` if
you want compilation and execution in a single step):

```bash
mvn clean compile exec:java -Dexec.args="--run-extended"
```

If you prefer to launch (and build) the sample while staying in the `google_adk` root, point Maven at
this module’s POM and again use a single command:

```bash
export GOOGLE_GENAI_USE_VERTEXAI=FALSE
export GOOGLE_API_KEY=your_api_key
mvn -f contrib/samples/mcpfilesystem/pom.xml clean compile exec:java
```

To run the extended sequence from the repo root, reuse the same environment variables and pass
`--run-extended`:

```bash
export GOOGLE_GENAI_USE_VERTEXAI=FALSE
export GOOGLE_API_KEY=your_api_key
mvn -f contrib/samples/mcpfilesystem/pom.xml clean compile exec:java -Dexec.args="--run-extended"
```

The extended flow drives the agent through:
- creating or overwriting `/tmp/mcp-demo/notes.txt` with default content
- reading the file back for confirmation
- searching the workspace for the seeded phrase
- appending a second line and displaying the updated file contents

## Related Samples

For the configuration-driven variant of this demo, see
`../configagent/tool_mcp_stdio_file_system_config/root_agent.yaml`.
