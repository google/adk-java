package com.google.adk.tools.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

public class McpSession {

    private final McpSyncClient client;
    private final McpSchema.InitializeResult initResult;

    public McpSession(McpSyncClient client, McpSchema.InitializeResult initResult) {
        this.client = client;
        this.initResult = initResult;
    }

    public McpSyncClient client() {
        return this.client;
    }

    public McpSchema.InitializeResult initResult() {
        return this.initResult;
    }
}
