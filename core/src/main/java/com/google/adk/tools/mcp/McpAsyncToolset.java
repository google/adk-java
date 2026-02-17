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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.NamedToolPredicate;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetrySpec;

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
public class McpAsyncToolset implements BaseToolset {

  private static final Logger logger = LoggerFactory.getLogger(McpAsyncToolset.class);

  private static final int MAX_RETRIES = 3;
  private static final Duration RETRY_DELAY = Duration.ofMillis(100);

  private final McpSessionManager mcpSessionManager;
  private final ObjectMapper objectMapper;
  private final Optional<Object> toolFilter;
  private final AtomicReference<Mono<List<McpAsyncTool>>> mcpTools = new AtomicReference<>();

  /** Builder for McpAsyncToolset */
  public static class Builder {
    private Object connectionParams = null;
    private ObjectMapper objectMapper = null;
    private Optional<Object> toolFilter = null;

    @CanIgnoreReturnValue
    public Builder connectionParams(ServerParameters connectionParams) {
      this.connectionParams = connectionParams;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder connectionParams(SseServerParameters connectionParams) {
      this.connectionParams = connectionParams;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder objectMapper(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder toolFilter(Optional<Object> toolFilter) {
      this.toolFilter = toolFilter;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder toolFilter(List<String> toolNames) {
      this.toolFilter = Optional.of(new NamedToolPredicate(toolNames));
      return this;
    }

    public McpAsyncToolset build() {
      if (objectMapper == null) {
        objectMapper = JsonBaseModel.getMapper();
      }
      if (connectionParams instanceof ServerParameters setSelectedParams) {
        return new McpAsyncToolset(setSelectedParams, objectMapper, toolFilter);
      } else if (connectionParams instanceof SseServerParameters sseServerParameters) {
        return new McpAsyncToolset(sseServerParameters, objectMapper, toolFilter);
      } else {
        throw new IllegalArgumentException(
            "connectionParams must be either ServerParameters or SseServerParameters");
      }
    }
  }

  /**
   * Initializes the McpAsyncToolset with SSE server parameters.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpAsyncToolset(
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
   * Initializes the McpAsyncToolset with local server parameters.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpAsyncToolset(
      ServerParameters connectionParams, ObjectMapper objectMapper, Optional<Object> toolFilter) {
    Objects.requireNonNull(connectionParams);
    Objects.requireNonNull(objectMapper);
    this.objectMapper = objectMapper;
    this.mcpSessionManager = new McpSessionManager(connectionParams);
    this.toolFilter = toolFilter;
  }

  /**
   * Initializes the McpAsyncToolset with a provided McpSessionManager.
   *
   * @param mcpSessionManager The session manager for MCP connections.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpAsyncToolset(
      McpSessionManager mcpSessionManager, ObjectMapper objectMapper, Optional<Object> toolFilter) {
    Objects.requireNonNull(mcpSessionManager);
    Objects.requireNonNull(objectMapper);
    this.objectMapper = objectMapper;
    this.mcpSessionManager = mcpSessionManager;
    this.toolFilter = toolFilter;
  }

  @Override
  public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
    return Maybe.defer(() -> Maybe.fromCompletionStage(this.initAndGetTools().toFuture()))
        .defaultIfEmpty(ImmutableList.of())
        .map(
            tools ->
                tools.stream()
                    .filter(
                        tool ->
                            isToolSelected(tool, toolFilter, Optional.ofNullable(readonlyContext)))
                    .toList())
        .onErrorResumeNext(
            err -> {
              if (err instanceof McpToolsetException) {
                return Single.error(err);
              } else {
                return Single.error(
                    new McpToolsetException.McpInitializationException(
                        "Failed to reinitialize session during tool loading retry (unexpected"
                            + " error).",
                        err));
              }
            })
        .flattenAsFlowable(it -> it);
  }

  private Mono<List<McpAsyncTool>> initAndGetTools() {
    return this.mcpTools.accumulateAndGet(
        null,
        (prev, _ignore) -> {
          if (prev == null) {
            // lazy init and cache tools
            return this.initTools().cache();
          }
          return prev;
        });
  }

  private Mono<List<McpAsyncTool>> initTools() {
    return Mono.defer(
            () -> {
              McpAsyncClient mcpSession = this.mcpSessionManager.createAsyncSession();
              return mcpSession
                  .initialize()
                  .doOnSuccess(
                      initResult -> logger.debug("Initialize Client Result: {}", initResult))
                  .thenReturn(mcpSession);
            })
        .flatMap(
            mcpSession ->
                mcpSession
                    .listTools()
                    .map(
                        toolsResponse ->
                            toolsResponse.tools().stream()
                                .map(
                                    tool ->
                                        new McpAsyncTool(
                                            tool,
                                            mcpSession, // move mcpSession to McpAsyncTool
                                            this.mcpSessionManager,
                                            this.objectMapper))
                                .toList()))
        .retryWhen(
            RetrySpec.from(
                retrySignal ->
                    retrySignal.flatMap(
                        signal -> {
                          Throwable err = signal.failure();
                          if (err instanceof IllegalArgumentException) {
                            // This could happen if parameters for tool loading are somehow
                            // invalid.
                            // This is likely a fatal error and should not be retried.
                            logger.error("Invalid argument encountered during tool loading.", err);
                            return Mono.error(
                                new McpToolsetException.McpToolLoadingException(
                                    "Invalid argument encountered during tool loading.", err));
                          }
                          long totalRetries = signal.totalRetries();
                          logger.error(
                              "Unexpected error during tool loading, retry attempt "
                                  + (totalRetries + 1),
                              err);
                          if (totalRetries < MAX_RETRIES) {
                            logger.info(
                                "Reinitializing MCP session before next retry for unexpected"
                                    + " error.");
                            return Mono.just(err).delayElement(RETRY_DELAY);
                          } else {
                            logger.error(
                                "Failed to load tools after multiple retries due to unexpected"
                                    + " error.",
                                err);
                            return Mono.error(
                                new McpToolsetException.McpToolLoadingException(
                                    "Failed to load tools after multiple retries due to unexpected"
                                        + " error.",
                                    err));
                          }
                        })));
  }

  @Override
  public void close() {
    Mono<List<McpAsyncTool>> tools = this.mcpTools.getAndSet(null);
    if (tools != null) {
      tools
          .flatMapIterable(it -> it)
          .flatMap(
              it ->
                  it.mcpSession
                      .closeGracefully()
                      .onErrorResume(
                          e -> {
                            logger.error("Failed to close MCP session", e);
                            // We don't throw an exception here, as closing is a cleanup operation
                            // and
                            // failing to close shouldn't prevent the program from continuing (or
                            // exiting).
                            // However, we log the error for debugging purposes.
                            return Mono.empty();
                          }))
          .doOnComplete(() -> logger.debug("MCP session closed successfully."))
          .subscribe();
    }
  }
}
