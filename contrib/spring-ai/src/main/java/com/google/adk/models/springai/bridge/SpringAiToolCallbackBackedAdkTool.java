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
import com.google.genai.types.Schema;
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
 * <p>Schema is extracted from {@link ToolCallback#getToolDefinition()}: {@code name}, {@code
 * description}, and the JSON Schema returned by {@code inputSchema()} are mapped into the ADK
 * {@link FunctionDeclaration}/{@link Schema} pair via {@link Schema#fromJson(String)}.
 *
 * <p>Invocation: the ADK runtime calls {@link #runAsync(Map, ToolContext)} with parsed arguments →
 * this class serializes them back to JSON and dispatches to {@link ToolCallback#call(String)},
 * which returns a JSON string. The string is parsed back into a {@code Map<String, Object>} for the
 * ADK flow. When the underlying callback's result is not a JSON object (e.g. a primitive or array),
 * the value is wrapped under a {@code "result"} key.
 */
public class SpringAiToolCallbackBackedAdkTool extends BaseTool {

  private static final Logger logger =
      LoggerFactory.getLogger(SpringAiToolCallbackBackedAdkTool.class);

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ToolCallback toolCallback;
  private final ObjectMapper objectMapper;
  private final Optional<FunctionDeclaration> declaration;

  /** Wraps a Spring AI {@link ToolCallback} as an ADK tool using the default ADK JSON mapper. */
  public SpringAiToolCallbackBackedAdkTool(ToolCallback toolCallback) {
    this(toolCallback, JsonBaseModel.getMapper());
  }

  /** Wraps a Spring AI {@link ToolCallback} as an ADK tool with a custom JSON mapper. */
  public SpringAiToolCallbackBackedAdkTool(ToolCallback toolCallback, ObjectMapper objectMapper) {
    super(
        Objects.requireNonNull(toolCallback, "toolCallback").getToolDefinition().name(),
        toolCallback.getToolDefinition().description());
    this.toolCallback = toolCallback;
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.declaration = buildDeclaration(toolCallback);
  }

  /**
   * Converts every {@link ToolCallback} in the input list into a {@link BaseTool}. Useful when
   * fanning out a {@code List<ToolCallback>} (e.g. the result of {@code McpToolCallbackProvider})
   * to an agent's {@code .tools(...)} list.
   */
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
          String responseJson = toolCallback.call(requestJson);
          return parseResponse(responseJson);
        });
  }

  private Map<String, Object> parseResponse(String responseJson) {
    if (responseJson == null || responseJson.isBlank()) {
      return ImmutableMap.of();
    }
    try {
      return objectMapper.readValue(responseJson, MAP_TYPE);
    } catch (Exception notAnObject) {
      // The callback returned a primitive, array, or arbitrary string. Wrap so the agent gets a
      // structured result rather than a parse failure.
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

  /** Exposed for tests and downstream tooling. */
  public ToolCallback toolCallback() {
    return toolCallback;
  }

  private static Optional<FunctionDeclaration> buildDeclaration(ToolCallback toolCallback) {
    var def = toolCallback.getToolDefinition();
    FunctionDeclaration.Builder builder = FunctionDeclaration.builder().name(def.name());
    if (def.description() != null && !def.description().isBlank()) {
      builder.description(def.description());
    }
    String inputSchema = def.inputSchema();
    if (inputSchema != null && !inputSchema.isBlank()) {
      try {
        builder.parameters(Schema.fromJson(inputSchema));
      } catch (Exception parseFailed) {
        logger.warn(
            "Could not parse Spring AI tool '{}' input schema as ADK Schema; falling back to raw"
                + " JSON schema. Cause: {}",
            def.name(),
            parseFailed.getMessage());
        builder.parametersJsonSchema(inputSchema);
      }
    }
    return Optional.of(builder.build());
  }
}
