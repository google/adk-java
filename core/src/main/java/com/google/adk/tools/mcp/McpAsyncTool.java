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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.util.Map;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

// TODO(b/413489523): Add support for auth. This is a TODO for Python as well.

/**
 * Initializes a MCP tool.
 *
 * <p>This wraps a MCP Tool interface and an active MCP Session. It invokes the MCP Tool through
 * executing the tool from remote MCP Session.
 */
public final class McpAsyncTool extends BaseTool {

    Tool mcpTool;
    Single<McpAsyncSession> mcpSession;
    McpSessionManager mcpSessionManager;
    ObjectMapper objectMapper;

    /**
     * Creates a new McpTool with the default ObjectMapper.
     *
     * @param mcpTool           The MCP tool to wrap.
     * @param mcpSession        The MCP session to use to call the tool.
     * @param mcpSessionManager The MCP session manager to use to create new sessions.
     * @throws IllegalArgumentException If mcpTool or mcpSession are null.
     */
    public McpAsyncTool(Tool mcpTool, Single<McpAsyncSession> mcpSession, McpSessionManager mcpSessionManager) {
        this(mcpTool, mcpSession, mcpSessionManager, JsonBaseModel.getMapper());
    }

    /**
     * Creates a new McpTool with the default ObjectMapper.
     *
     * @param mcpTool           The MCP tool to wrap.
     * @param mcpSession        The MCP session to use to call the tool.
     * @param mcpSessionManager The MCP session manager to use to create new sessions.
     * @param objectMapper      The ObjectMapper to use to convert JSON schemas.
     * @throws IllegalArgumentException If mcpTool or mcpSession are null.
     */
    public McpAsyncTool(
            Tool mcpTool,
            Single<McpAsyncSession> mcpSession,
            McpSessionManager mcpSessionManager,
            ObjectMapper objectMapper) {
        super(
                mcpTool == null ? "" : mcpTool.name(),
                mcpTool == null ? "" : (mcpTool.description().isEmpty() ? "" : mcpTool.description()));

        if (mcpTool == null) {
            throw new IllegalArgumentException("mcpTool cannot be null");
        }
        if (mcpSession == null) {
            throw new IllegalArgumentException("mcpSession cannot be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper cannot be null");
        }
        this.mcpTool = mcpTool;
        this.mcpSession = mcpSession;
        this.mcpSessionManager = mcpSessionManager;
        this.objectMapper = objectMapper;
    }

    public Single<McpAsyncSession> mcpSession() {
        return this.mcpSession;
    }

    public Schema toGeminiSchema(JsonSchema openApiSchema) {
        return Schema.fromJson(objectMapper.valueToTree(openApiSchema).toString());
    }

    private void reintializeSession() {
        this.mcpSession = this.mcpSessionManager.createAsyncSession();
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(this.name())
                        .description(this.description())
                        .parameters(toGeminiSchema(this.mcpTool.inputSchema()))
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.defer(() ->
                        this.mcpSession.flatMapMaybe(session ->
                                Maybe.fromCompletionStage(
                                        session.client().callTool(new CallToolRequest(this.name(), ImmutableMap.copyOf(args)))
                                                .toFuture()
                                )
                        ).map(callResult -> McpTool.wrapCallResult(
                                this.objectMapper, this.name(), callResult)
                        ).switchIfEmpty(
                                Single.fromCallable(
                                        () -> McpTool.wrapCallResult(this.objectMapper, this.name(), null)
                                )
                        )
                )
                .retryWhen(
                        errors ->
                                errors
                                        .delay(100, MILLISECONDS)
                                        .take(3)
                                        .doOnNext(
                                                error -> {
                                                    System.err.println("Retrying callTool due to: " + error);
                                                    reintializeSession();
                                                }));
    }
}
