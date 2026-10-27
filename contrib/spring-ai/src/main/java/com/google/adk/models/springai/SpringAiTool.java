/*
 * Copyright 2026 Google LLC
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
package com.google.adk.models.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes a Spring AI {@link ToolCallback} as an ADK {@link BaseTool}, so an existing Spring AI
 * tool can be used by any ADK agent and model. Name, description, and input schema come from the
 * callback's {@link ToolDefinition}; the schema is passed through as {@link
 * FunctionDeclaration#parametersJsonSchema()}. The ADK {@link ToolContext} is passed to the
 * callback under {@link ToolConverter#ADK_TOOL_CONTEXT_KEY}, and a {@code returnDirect} callback
 * maps to ADK's {@code skipSummarization}.
 */
public final class SpringAiTool extends BaseTool {

  private static final Logger logger = LoggerFactory.getLogger(SpringAiTool.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final ImmutableMap<String, Object> EMPTY_OBJECT_SCHEMA =
      ImmutableMap.of("type", "object", "properties", ImmutableMap.of());

  private final ToolCallback toolCallback;
  private final FunctionDeclaration declaration;

  /**
   * Wraps {@code toolCallback} as an ADK tool.
   *
   * @param toolCallback The Spring AI tool to wrap
   */
  public SpringAiTool(ToolCallback toolCallback) {
    this(toolCallback, definitionOf(toolCallback));
  }

  private SpringAiTool(ToolCallback toolCallback, ToolDefinition toolDefinition) {
    super(
        Objects.requireNonNull(toolDefinition.name(), "ToolDefinition name cannot be null"),
        toolDefinition.description() != null ? toolDefinition.description() : "");
    this.toolCallback = toolCallback;
    // The wrapped ToolDefinition cannot change, so parse its schema once rather than per request.
    FunctionDeclaration.Builder declarationBuilder =
        FunctionDeclaration.builder().name(name()).description(description());
    Map<String, Object> schema = parseInputSchema(name(), toolDefinition.inputSchema());
    if (!schema.isEmpty()) {
      declarationBuilder.parametersJsonSchema(schema);
    }
    this.declaration = declarationBuilder.build();
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(declaration);
  }

  @Override
  public Single<Map<String, Object>> runAsync(
      @Nullable Map<String, Object> args, @Nullable ToolContext toolContext) {
    return Single.fromCallable(
        () -> {
          logger.debug("ADK calling Spring AI tool '{}'", name());
          String toolInput;
          try {
            toolInput = objectMapper.writeValueAsString(args != null ? args : Map.of());
          } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to serialize arguments for tool '" + name() + "'", e);
          }

          String rawResult = toolCallback.call(toolInput, springAiToolContextFrom(toolContext));

          if (toolContext != null
              && toolContext.actions() != null
              && toolCallback.getToolMetadata() != null
              && toolCallback.getToolMetadata().returnDirect()) {
            toolContext.actions().setSkipSummarization(true);
          }

          return ToolConverter.parseToolResult(rawResult);
        });
  }

  private static ToolDefinition definitionOf(ToolCallback toolCallback) {
    Objects.requireNonNull(toolCallback, "toolCallback cannot be null");
    return Objects.requireNonNull(
        toolCallback.getToolDefinition(), "toolCallback.getToolDefinition() cannot be null");
  }

  /**
   * Parses the callback's input schema; a null or blank schema means no parameters, and an
   * unparseable one falls back to an empty object schema, as in ADK Kotlin, so one broken tool does
   * not fail the agent.
   */
  private static Map<String, Object> parseInputSchema(String toolName, @Nullable String schema) {
    if (schema == null || schema.isBlank()) {
      return ImmutableMap.of();
    }
    try {
      return ToolConverter.parseJsonSchemaMap(schema);
    } catch (IllegalArgumentException e) {
      logger.warn(
          "Spring AI tool '{}' has an unparseable input schema; using an empty object schema",
          toolName);
      return EMPTY_OBJECT_SCHEMA;
    }
  }

  private static org.springframework.ai.chat.model.ToolContext springAiToolContextFrom(
      @Nullable ToolContext adkToolContext) {
    if (adkToolContext == null) {
      return new org.springframework.ai.chat.model.ToolContext(Map.of());
    }
    return new org.springframework.ai.chat.model.ToolContext(
        Map.of(ToolConverter.ADK_TOOL_CONTEXT_KEY, adkToolContext));
  }
}
