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

import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

/**
 * Manages MCP client sessions.
 *
 * <p>This class provides methods for creating and initializing MCP client sessions, handling
 * different connection parameters (Stdio and SSE).
 */
// TODO(b/413489523): Implement this class.
public class McpSessionManager {

  private final Object connectionParams; // ServerParameters or SseServerParameters
  private static final Logger logger = LoggerFactory.getLogger(McpSessionManager.class);

  public McpSessionManager(Object connectionParams) {
    this.connectionParams = connectionParams;
  }

  public McpSyncClient createSession() {
    return initializeSession(this.connectionParams);
  }

  public static McpSyncClient initializeSession(Object connectionParams) {
    McpClientTransport transport;
    if (connectionParams instanceof ServerParameters serverParameters) {
      transport = new StdioClientTransport(serverParameters);
    } else if (connectionParams instanceof SseServerParameters sseServerParams) {
        transport = HttpClientSseClientTransport.builder(sseServerParams.url())
                .sseEndpoint("sse")
                .customizeRequest(builder ->
                        Optional.ofNullable(sseServerParams.headers())
                                .map(ImmutableMap::entrySet)
                                .stream().flatMap(Collection::stream)
                                .forEach(entry ->
                                        builder.header(
                                                entry.getKey(),
                                                Optional.ofNullable(entry.getValue())
                                                        .map(Object::toString)
                                                        .orElse("")
                                        )
                                )
                )
                .build();
    } else {
      throw new IllegalArgumentException(
          "Connection parameters must be either ServerParameters or SseServerParameters, but got "
              + connectionParams.getClass().getName());
    }
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
