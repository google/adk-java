# Cassandra ANN Search Implementation

**Author:** Sandeep Belgavi
**Date:** October 19, 2025

## Overview

This document provides a detailed explanation of the Approximate Nearest Neighbor (ANN) search implementation using Cassandra within the Google Agent Development Kit (ADK). This feature allows an agent to perform similarity searches on vector embeddings stored in a Cassandra database, enabling more advanced retrieval capabilities.

## Core Components

### `CassandraRagRetrieval.java`

This class is a retrieval tool that executes ANN queries against a Cassandra database. It is designed to be used by the `CassandraMemoryService` but can also be used as a standalone tool within an agent.

**Key Methods:**

*   `runAsync(Map<String, Object> args, ToolContext toolContext)`: This method takes a map of arguments, which must include an `embedding` (a list of floats), and optionally `top_k`, `keyspace`, `table`, and `embedding_column`. It constructs and executes a CQL query to find the `top_k` most similar vectors to the provided embedding.

### `CassandraMemoryService.java`

This class implements the `BaseMemoryService` interface and uses the `CassandraRagRetrieval` tool to provide a searchable memory for the agent.

**Key Methods:**

*   `searchMemory(String appName, String userId, String query)`: This method takes a query string, which is expected to be a vector embedding, and uses the `CassandraRagRetrieval` tool to find the most similar memories.

## Cassandra Table Structure for ANN

To use the ANN search functionality, you need a Cassandra table with a `vector` column and a Storage-Attached Index (SAI) on that column. Here is an example of a table structure that can be used for ANN search:

```cql
CREATE TABLE IF NOT EXISTS rae.rae_data (
    client_id TEXT,
    session_id TEXT,
    data TEXT,
    embedding VECTOR<FLOAT, 768>,
    PRIMARY KEY (client_id, session_id)
);

CREATE CUSTOM INDEX IF NOT EXISTS ON adk.route_embedding (embedding) USING 'org.apache.cassandra.index.sai.StorageAttachedIndex';
```

**Explanation:**

*   `id`: A unique identifier for each entry.
*   `data`: A text field to store any additional data associated with the embedding.
*   `embedding`: A `vector` column to store the vector embeddings. The example above uses a dimension of 768, but this should be adjusted to match the dimension of your embeddings.
*   **SAI Index:** A Storage-Attached Index is required on the `embedding` column to enable ANN search.

## `curl` Command for ANN Search

You can use the following `curl` command to test the ANN search functionality. This command sends a JSON-RPC 2.0 request to the agent, which then uses the `CassandraRagRetrieval` tool to perform the ANN search.

```bash
curl -X POST \
  http://127.0.0.1:8080/ \
  -H 'Content-Type: application/json' \
  -d 
    "jsonrpc": "2.0",
    "id": "req-001",
    "method": "agent.run",
    "params": {
      "app_name": "myApp",
      "user_id": "user123",
      "session_id": "session123",
      "message": {
        "parts": [
          {
            "text": "Find similar routes"
          }
        ]
      },
      "embedding": [0.1, 0.2, 0.3, ...],
      "keyspace": "adk",
      "table": "route_embedding",
      "embedding_column": "embedding"
    }
  
```

**Note:** You will need to replace the `embedding` with your actual embedding vector.
