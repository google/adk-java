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
 * different connection parameters and transport builders.
 */
// TODO(b/413489523): Implement this class.
public class McpSessionManager {

  private final Object connectionParams; // ServerParameters or SseServerParameters
  private static final Logger logger = LoggerFactory.getLogger(McpSessionManager.class);
  private final McpTransportBuilder transportBuilder;

  public McpSessionManager(Object connectionParams) {
    this(connectionParams, new DefaultMcpTransportBuilder());
  }

  public McpSessionManager(Object connectionParams, McpTransportBuilder transportBuilder) {
    this.connectionParams = connectionParams;
    this.transportBuilder = transportBuilder;
  }

  public McpSyncClient createSession() {
    return initializeSession(this.connectionParams, this.transportBuilder);
  }

  public static McpSyncClient initializeSession(Object connectionParams) {
    return initializeSession(connectionParams, new DefaultMcpTransportBuilder());
  }

  public static McpSyncClient initializeSession(
      Object connectionParams, McpTransportBuilder transportBuilder) {
    McpClientTransport transport = transportBuilder.build(connectionParams);

    McpSyncClient client =
        McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(10))
            .capabilities(ClientCapabilities.builder().build())
            .build();
    InitializeResult initResult = client.initialize();
    logger.debug("Initialize Client Result: {}", initResult);

    return client;
  }
}
