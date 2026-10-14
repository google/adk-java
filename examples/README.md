# ADK Java examples

This directory contains minimal examples that each show one or a few ADK
features. Each example is its own Maven module, and its README shows how to run
it.

These examples differ from the
[google/adk-samples](https://github.com/google/adk-samples) repository, which
contains more complete end-to-end samples for customers to use or modify.

-   [`a2a_basic`](a2a_basic/README.md): an ADK agent that calls a remote agent
    over A2A.
-   [`a2a_server`](a2a_server/README.md): an ADK agent served over A2A with
    Quarkus.
-   [`configagent`](configagent/README.md): agents defined in YAML config files.
-   [`contextcaching`](contextcaching/README.md): Gemini context caching that
    reuses and refreshes a cache across turns.
-   [`helloworld`](helloworld/README.md): a minimal agent with dice-rolling and
    prime-checking tools.
-   [`mcpfilesystem`](mcpfilesystem/README.md): an agent that uses the MCP
    filesystem server as a toolset.
