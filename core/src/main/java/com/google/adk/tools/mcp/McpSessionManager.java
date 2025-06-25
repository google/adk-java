/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.tools.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters; // Server Parameters for stdio.
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages MCP client sessions.
 *
 * <p>This class provides methods for creating and initializing MCP client sessions, handling
 * different connection parameters (Stdio and SSE).
 */
// TODO(b/413489523): Implement this class.
public class McpSessionManager {

  private static final Logger logger = LoggerFactory.getLogger(McpSessionManager.class);
  private final McpClientTransport transport;

  public McpSessionManager(Object connectionParams) {
    this.transport = createTransport(connectionParams);
  }

  public McpSessionManager(McpClientTransport transport) {
    this.transport = transport;
  }

  public McpSyncClient createSession() {
    return initializeSession(this.transport);
  }

  public static McpSyncClient initializeSession(Object connectionParams) {
    McpClientTransport transport = createTransport(connectionParams);
    return initializeSession(transport);
  }

  public static McpSyncClient initializeSession(McpClientTransport transport) {
    McpSyncClient client =
        McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(10))
            .capabilities(ClientCapabilities.builder().build())
            .build();
    InitializeResult initResult = client.initialize();
    logger.debug("Initialize Client Result: {}", initResult);

    return client;
  }

  private static McpClientTransport createTransport(Object connectionParams) {
    if (connectionParams instanceof ServerParameters serverParameters) {
      return new StdioClientTransport(serverParameters);
    } else if (connectionParams instanceof SseServerParameters sseServerParams) {
      return HttpClientSseClientTransport.builder(sseServerParams.url()).sseEndpoint("sse").build();
    } else {
      throw new IllegalArgumentException(
          "Connection parameters must be either ServerParameters or SseServerParameters, but got "
              + connectionParams.getClass().getName());
    }
  }
}
