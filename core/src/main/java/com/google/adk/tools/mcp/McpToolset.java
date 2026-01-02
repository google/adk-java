/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.tools.mcp;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.common.primitives.Booleans;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Connects to a MCP Server, and retrieves MCP Tools into ADK Tools.
 *
 * <p>Attributes:
 *
 * <ul>
 *   <li>{@code connectionParams}: The connection parameters to the MCP server. Can be either {@code
 *       ServerParameters} or {@code SseServerParameters}.
 *   <li>{@code session}: The MCP session being initialized with the connection.
 * </ul>
 */
public class McpToolset implements BaseToolset {
  private static final Logger logger = LoggerFactory.getLogger(McpToolset.class);
  private final McpSessionManager mcpSessionManager;
  private McpAsyncClient mcpSession;
  private final ObjectMapper objectMapper;
  private final Optional<Object> toolFilter;

  private static final int MAX_RETRIES = 3;
  private static final long RETRY_DELAY_MILLIS = 100;
  protected static final Class<? extends McpToolsetConfig> CONFIG_TYPE = McpToolsetConfig.class;

  /**
   * Initializes the McpToolset with SSE server parameters.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(
      SseServerParameters connectionParams,
      ObjectMapper objectMapper,
      Optional<Object> toolFilter) {
    Objects.requireNonNull(connectionParams);
    Objects.requireNonNull(objectMapper);
    this.objectMapper = objectMapper;
    this.mcpSessionManager = new McpSessionManager(connectionParams);
    this.toolFilter = toolFilter;
  }

  /**
   * Initializes the McpToolset with SSE server parameters and no tool filter.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   */
  public McpToolset(SseServerParameters connectionParams, ObjectMapper objectMapper) {
    this(connectionParams, objectMapper, Optional.empty());
  }

  /**
   * Initializes the McpToolset with local server parameters.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(
      ServerParameters connectionParams, ObjectMapper objectMapper, Optional<Object> toolFilter) {
    Objects.requireNonNull(connectionParams);
    Objects.requireNonNull(objectMapper);
    this.objectMapper = objectMapper;
    this.mcpSessionManager = new McpSessionManager(connectionParams);
    this.toolFilter = toolFilter;
  }

  /**
   * Initializes the McpToolset with local server parameters and no tool filter.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   */
  public McpToolset(ServerParameters connectionParams, ObjectMapper objectMapper) {
    this(connectionParams, objectMapper, Optional.empty());
  }

  /**
   * Initializes the McpToolset with SSE server parameters, using the ObjectMapper used across the
   * ADK.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(SseServerParameters connectionParams, Optional<Object> toolFilter) {
    this(connectionParams, JsonBaseModel.getMapper(), toolFilter);
  }

  /**
   * Initializes the McpToolset with SSE server parameters, using the ObjectMapper used across the
   * ADK and no tool filter.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   */
  public McpToolset(SseServerParameters connectionParams) {
    this(connectionParams, JsonBaseModel.getMapper(), Optional.empty());
  }

  /**
   * Initializes the McpToolset with local server parameters, using the ObjectMapper used across the
   * ADK.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(ServerParameters connectionParams, Optional<Object> toolFilter) {
    this(connectionParams, JsonBaseModel.getMapper(), toolFilter);
  }

  /**
   * Initializes the McpToolset with local server parameters, using the ObjectMapper used across the
   * ADK and no tool filter.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   */
  public McpToolset(ServerParameters connectionParams) {
    this(connectionParams, JsonBaseModel.getMapper(), Optional.empty());
  }

  /**
   * Initializes the McpToolset with an McpSessionManager.
   *
   * @param mcpSessionManager A McpSessionManager instance for testing.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(
      McpSessionManager mcpSessionManager, ObjectMapper objectMapper, Optional<Object> toolFilter) {
    Objects.requireNonNull(mcpSessionManager);
    Objects.requireNonNull(objectMapper);
    this.mcpSessionManager = mcpSessionManager;
    this.objectMapper = objectMapper;
    this.toolFilter = toolFilter;
  }

  /**
   * Initializes the McpToolset with Steamable HTTP server parameters.
   *
   * @param connectionParams The Streamable HTTP connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(
      StreamableHttpServerParameters connectionParams,
      ObjectMapper objectMapper,
      Optional<Object> toolFilter) {
    Objects.requireNonNull(connectionParams);
    Objects.requireNonNull(objectMapper);
    this.objectMapper = objectMapper;
    this.mcpSessionManager = new McpSessionManager(connectionParams);
    this.toolFilter = toolFilter;
  }

  /**
   * Initializes the McpToolset with Streamable HTTP server parameters, using the ObjectMapper used
   * across the ADK and no tool filter.
   *
   * @param connectionParams The Streamable HTTP connection parameters to the MCP server.
   */
  public McpToolset(StreamableHttpServerParameters connectionParams) {
    this(connectionParams, JsonBaseModel.getMapper(), Optional.empty());
  }

  @Override
  public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
    return Flowable.defer(
            () -> {
              if (this.mcpSession == null) {
                logger.info("MCP session is null, initializing.");
                this.mcpSession = this.mcpSessionManager.createAsyncSession();
              }

              // Retrieve tools from the MCP session, wrap them in McpTool, filter them, and return
              // as a Flowable.
              Mono<ListToolsResult> toolsResponse = this.mcpSession.listTools();
              return Flowable.fromPublisher(
                  toolsResponse
                      .flatMapMany(result -> Flux.fromIterable(result.tools()))
                      .map(
                          tool ->
                              new McpTool(
                                  tool, this.mcpSession, this.mcpSessionManager, this.objectMapper))
                      .filter(
                          tool ->
                              isToolSelected(
                                  tool, toolFilter, Optional.ofNullable(readonlyContext))));
            })
        .retryWhen(
            errorObservable ->
                errorObservable.zipWith(
                    Flowable.range(1, MAX_RETRIES),
                    (error, retryCount) -> {
                      if (error instanceof IllegalArgumentException) {
                        // This could happen if parameters for tool loading are somehow invalid.
                        // This is likely a fatal error and should not be retried.
                        logger.error("Invalid argument encountered during tool loading.", error);
                        throw new McpToolsetException.McpToolLoadingException(
                            "Invalid argument encountered during tool loading.", error);
                      } else if (error instanceof RuntimeException) {
                        // Catch any other unexpected runtime exceptions
                        logger.error(
                            "Unexpected error during tool loading, retry attempt " + retryCount,
                            error);
                        logger.info(
                            "Reinitializing MCP session before next retry for unexpected error.");
                        this.mcpSession = null;

                        if (retryCount < MAX_RETRIES) {
                          // For other general exceptions, we might still want to retry if they are
                          // potentially transient, or if we don't have more specific handling. But
                          // it's better to be specific. For now, we'll treat them as potentially
                          // retryable but log them at a higher level.

                          // Delay before retrying
                          return Flowable.timer(RETRY_DELAY_MILLIS, MILLISECONDS);
                        } else {
                          logger.error(
                              "Failed to load tools after multiple retries due to unexpected"
                                  + " error.",
                              error);
                          throw new McpToolsetException.McpToolLoadingException(
                              "Failed to load tools after multiple retries due to unexpected"
                                  + " error.",
                              error);
                        }
                      }
                      // This line should ideally not be reached if retries are handled correctly or
                      // an exception is always thrown.
                      // If an unhandled error type occurs, propagate it.
                      return Flowable.error(error);
                    }))
        .map(tools -> tools);
  }

  @Override
  public void close() {
    if (this.mcpSession != null) {
      try {
        this.mcpSession.close();
        logger.debug("MCP session closed successfully.");
      } catch (RuntimeException e) {
        logger.error("Failed to close MCP session", e);
        // We don't throw an exception here, as closing is a cleanup operation and
        // failing to close shouldn't prevent the program from continuing (or exiting).
        // However, we log the error for debugging purposes.
      } finally {
        this.mcpSession = null;
      }
    }
  }

  /** Configuration class for MCPToolset. */
  public static class McpToolsetConfig extends JsonBaseModel {

    private StdioConnectionParameters stdioConnectionParams;

    private StdioServerParameters stdioServerParams;

    private SseServerParameters sseServerParams;

    private List<String> toolFilter;

    public StdioConnectionParameters stdioConnectionParams() {
      return stdioConnectionParams;
    }

    public void setStdioConnectionParams(StdioConnectionParameters stdioConnectionParams) {
      this.stdioConnectionParams = stdioConnectionParams;
    }

    public StdioServerParameters stdioServerParams() {
      return stdioServerParams;
    }

    public void setStdioServerParams(StdioServerParameters stdioServerParams) {
      this.stdioServerParams = stdioServerParams;
    }

    public SseServerParameters sseServerParams() {
      return sseServerParams;
    }

    public void setSseServerParams(SseServerParameters sseServerParams) {
      this.sseServerParams = sseServerParams;
    }

    public List<String> toolFilter() {
      return toolFilter;
    }

    public void setToolFilter(List<String> toolFilter) {
      this.toolFilter = toolFilter;
    }
  }

  /**
   * Creates a McpToolset instance from a config.
   *
   * @param config The config for the McpToolset.
   * @param configAbsPath The absolute path to the config file that contains the McpToolset config.
   * @return The McpToolset instance.
   * @throws ConfigurationException if the McpToolset cannot be created from the config.
   */
  public static McpToolset fromConfig(BaseTool.ToolConfig config, String configAbsPath)
      throws ConfigurationException {
    if (config.args() == null) {
      throw new ConfigurationException("Tool args is null for McpToolset");
    }

    ObjectMapper mapper = JsonBaseModel.getMapper();
    try {
      // Convert ToolArgsConfig to McpToolsetConfig
      McpToolsetConfig mcpToolsetConfig =
          mapper.convertValue(config.args(), McpToolsetConfig.class);

      // Validate that exactly one parameter type is set
      if (Booleans.countTrue(
              mcpToolsetConfig.stdioServerParams() != null,
              mcpToolsetConfig.sseServerParams() != null,
              mcpToolsetConfig.stdioConnectionParams() != null)
          != 1) {
        throw new ConfigurationException(
            "Exactly one of stdioConnectionParams, stdioServerParams or sseServerParams must be set"
                + " for McpToolset");
      }

      // Convert tool filter to Optional<Object>
      Optional<Object> toolFilter = Optional.ofNullable(mcpToolsetConfig.toolFilter());

      Object connectionParameters =
          Optional.<Object>ofNullable(mcpToolsetConfig.stdioConnectionParams())
              .or(() -> Optional.ofNullable(mcpToolsetConfig.sseServerParams()))
              .orElse(mcpToolsetConfig.stdioConnectionParams());

      // Create McpToolset with McpSessionManager having appropriate connection parameters
      return new McpToolset(new McpSessionManager(connectionParameters), mapper, toolFilter);
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException("Failed to parse McpToolsetConfig from ToolArgsConfig", e);
    }
  }
}
