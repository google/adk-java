# MCP Full Result Agent Sample

By default, an MCP tool returns only the text of its result, under
`text_output`. This sample sets `CallToolResultMapper.fullResult()` on the
toolset, so the tool returns the whole result: `content`, plus
`structuredContent`, `isError` and `_meta` when set. An `afterToolCallbackSync`
then reshapes it before the model sees it.

The sample uses the BigQuery MCP server at
`https://bigquery.googleapis.com/mcp`. As the MCP specification recommends, it
returns `structuredContent` and repeats it as text, so the callback keeps only
`structuredContent` to avoid sending the same data twice.

## Project Layout

```
├── McpFullResultAgent.java  // Agent, MCP toolset and after-tool callback
├── McpFullResultRun.java    // Console runner entry point
├── pom.xml                  // Maven configuration and exec main class
└── README.md                // This file
```

## Prerequisites

-   Java 17+
-   Maven 3.9+
-   A Google Cloud project with the BigQuery and Vertex AI APIs enabled
-   The MCP Tool User role (`roles/mcp.toolUser`) on that project, as the
    [BigQuery MCP server guide](https://docs.cloud.google.com/bigquery/docs/use-bigquery-mcp)
    describes
-   Application Default Credentials (`gcloud auth application-default login`)

## Build and Run

Set the Gemini and MCP environment variables and run the sample from this
directory:

```bash
export GOOGLE_GENAI_USE_VERTEXAI=TRUE
export GOOGLE_CLOUD_PROJECT=your-project
export GOOGLE_CLOUD_LOCATION=global
export MCP_ACCESS_TOKEN=$(gcloud auth application-default print-access-token)
mvn clean compile exec:java
```

The runner asks for three dataset ids in `bigquery-public-data` and prints the
tool response the model received, which holds only the structured content:

```
You> List three dataset ids in the bigquery-public-data project.
Tool response sent to the model> {result={datasets=[{id=bigquery-public-data:...}, ...]}}
Agent> ...
```

To run it from the `google_adk` root, point Maven at this module's POM:

```bash
mvn -f examples/mcpfullresult/pom.xml clean compile exec:java
```
