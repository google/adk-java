/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.adk.models.springai.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * ADK {@link BaseTool} backed by a Spring AI {@link ToolCallback}.
 *
 * <p>Adapts <strong>any</strong> Spring AI {@code ToolCallback} (including {@code
 * SyncMcpToolCallback} / {@code AsyncMcpToolCallback} from {@code spring-ai-starter-mcp-client},
 * {@code FunctionToolCallback}, {@code @Tool}-annotated method callbacks, etc.) into the ADK tool
 * model so they can be attached to an {@link com.google.adk.agents.LlmAgent}.
 *
 * <p><strong>Schema preservation:</strong> the JSON Schema returned by {@code
 * ToolCallback.getToolDefinition().inputSchema()} is parsed into a structured Map and set via
 * {@code FunctionDeclaration.parametersJsonSchema(...)} rather than {@code
 * parameters(Schema.fromJson(...))}. This routes through the faithful branch of the downstream
 * Spring AI {@code ToolConverter}, preserving {@code items}, {@code enum}, {@code format}, {@code
 * anyOf}/{@code oneOf}, {@code additionalProperties}, {@code $defs} and {@code $ref} — none of
 * which survive the {@code parameters(Schema)} path today.
 *
 * <p><strong>Invocation & errors:</strong> {@link #runAsync(Map, ToolContext)} serializes args to
 * JSON, dispatches to {@link ToolCallback#call(String)}, and parses the JSON response back. Any
 * exception thrown by the callback is caught and translated to {@code Map.of("error", "<message>")}
 * — matching the shape used by ADK's native {@code AbstractMcpTool.wrapCallResult(...)} — so the
 * agent flow sees a structured error result instead of a hard failure on the {@link Single}.
 */
public class SpringAiToolCallbackBackedAdkTool extends BaseTool {

  private static final Logger logger =
      LoggerFactory.getLogger(SpringAiToolCallbackBackedAdkTool.class);

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ToolCallback toolCallback;
  private final ObjectMapper objectMapper;
  private final Optional<FunctionDeclaration> declaration;

  public SpringAiToolCallbackBackedAdkTool(ToolCallback toolCallback) {
    this(toolCallback, JsonBaseModel.getMapper());
  }

  public SpringAiToolCallbackBackedAdkTool(ToolCallback toolCallback, ObjectMapper objectMapper) {
    super(
        Objects.requireNonNull(toolCallback, "toolCallback").getToolDefinition().name(),
        toolCallback.getToolDefinition().description());
    this.toolCallback = toolCallback;
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.declaration = buildDeclaration(toolCallback, objectMapper);
  }

  public static List<BaseTool> wrapAll(List<? extends ToolCallback> toolCallbacks) {
    return toolCallbacks.stream()
        .map(SpringAiToolCallbackBackedAdkTool::new)
        .collect(Collectors.toList());
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return declaration;
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    return Single.fromCallable(
        () -> {
          String requestJson = objectMapper.writeValueAsString(args == null ? Map.of() : args);
          if (logger.isDebugEnabled()) {
            logger.debug("Invoking Spring AI tool '{}' with args: {}", name(), requestJson);
          }
          try {
            String responseJson = toolCallback.call(requestJson);
            return parseResponse(responseJson);
          } catch (Exception callFailed) {
            // Match AbstractMcpTool.wrapCallResult(...) — return a structured error map rather
            // than propagate the raw exception through the ADK Single, so the agent flow can
            // observe the failure as a tool result instead of aborting the invocation.
            logger.warn(
                "Spring AI tool '{}' invocation failed: {}", name(), callFailed.getMessage());
            return ImmutableMap.of(
                "error",
                callFailed.getMessage() == null
                    ? callFailed.getClass().getSimpleName()
                    : callFailed.getMessage());
          }
        });
  }

  private Map<String, Object> parseResponse(String responseJson) {
    if (responseJson == null || responseJson.isBlank()) {
      return ImmutableMap.of();
    }
    try {
      return objectMapper.readValue(responseJson, MAP_TYPE);
    } catch (Exception notAnObject) {
      Object decoded = tryDecodeAsJsonValue(responseJson);
      return ImmutableMap.of("result", decoded);
    }
  }

  private Object tryDecodeAsJsonValue(String responseJson) {
    try {
      return objectMapper.readTree(responseJson);
    } catch (Exception notJson) {
      return responseJson;
    }
  }

  public ToolCallback toolCallback() {
    return toolCallback;
  }

  private static Optional<FunctionDeclaration> buildDeclaration(
      ToolCallback toolCallback, ObjectMapper objectMapper) {
    var def = toolCallback.getToolDefinition();
    FunctionDeclaration.Builder builder = FunctionDeclaration.builder().name(def.name());
    if (def.description() != null && !def.description().isBlank()) {
      builder.description(def.description());
    }
    String inputSchema = def.inputSchema();
    if (inputSchema != null && !inputSchema.isBlank()) {
      try {
        // Pass the JSON Schema through verbatim, parsed to a Map (not a raw String).
        // ToolConverter serializes parametersJsonSchema faithfully — preserving items, enum,
        // format, anyOf/oneOf, additionalProperties, $defs, $ref. Using parameters(Schema.fromJson)
        // instead would route through a lossy branch that drops most of those.
        builder.parametersJsonSchema(objectMapper.readValue(inputSchema, MAP_TYPE));
      } catch (Exception schemaUnparseable) {
        // Genuinely malformed JSON: leave parameters unset rather than emit a degraded schema.
        logger.warn(
            "Spring AI tool '{}' has an unparseable input schema; declaring it with no parameter"
                + " schema. Cause: {}",
            def.name(),
            schemaUnparseable.getMessage());
      }
    }
    return Optional.of(builder.build());
  }
}
