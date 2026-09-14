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

package com.google.adk.tools.environmentsimulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Answers a tool call with a response a model writes from the tool's own specification. */
final class ToolSpecMockStrategy {

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)\\}");

  private static final String PROMPT_TEMPLATE =
      """
      You are a stateful tool simulator. Your task is to generate a
      realistic JSON response for a tool call, maintaining consistency based
      on a shared state.

      {environment_data_snippet}

      {tracing_snippet}

      Here is the map of how tools connect via stateful parameters:
      {tool_connection_map_json}

      Here is the current state of all stateful parameters:
      {state_store_json}

      You are now simulating the following tool call:
      Tool Name: {tool_name}
      Tool Description: {tool_description}
      Tool Schema: {tool_schema_json}
      Tool Arguments: {tool_arguments_json}

      Your instructions:
      1.  Analyze the tool call. Is it a "creating" or "consuming" tool
          based on the connection map?
      2.  If it's a "consuming" tool, check the provided arguments against
          the state store. If an ID is provided that does not exist in the
          state, return a realistic error (e.g., a 404 Not Found error).
          Otherwise, use the data from the state, the provided environment data,
          and the tracing history to generate the response.
      3.  If it's a "creating" tool, generate a new, unique ID for the
          stateful parameter (e.g., a random string for a ticket_id). Include
          this new ID in your response. I will then update the state with it.
      4.  Leverage the provided environment data (if any) to make your response
          more realistic and consistent with the simulated environment.
      5.  Leverage the provided tracing history (if any) to make your response
          consistent with observed tool behavior patterns from prior runs.
      6.  Generate a convincing, valid JSON object that mocks the tool's
          response. The response must be only the JSON object, without any
          additional text or formatting.
      7.  The response must start with '{' and end with '}'.
      """;

  private static final String ENVIRONMENT_DATA_SNIPPET_TEMPLATE =
      """
      Here is relevant environment data (e.g., database snippet, context information):
      <environment_data>
      {environment_data}
      </environment_data>
      Use this information to generate more realistic responses.
      """;

  private static final String TRACING_SNIPPET_TEMPLATE =
      """
      Here is a tracing history from a prior agent run (e.g., recorded tool
      calls and responses):
      <tracing>
      {tracing}
      </tracing>
      Use this history to make your mock responses consistent with observed
      tool behavior patterns.
      """;

  private final String modelName;
  private final GenerateContentConfig modelConfig;

  ToolSpecMockStrategy(String modelName, GenerateContentConfig modelConfig) {
    this.modelName = modelName;
    this.modelConfig = modelConfig;
  }

  /**
   * Generates a mock response for one tool call, and records anything the tool created in the state
   * store so that later calls stay consistent with it.
   */
  Single<Map<String, Object>> mock(
      BaseTool tool,
      Map<String, Object> args,
      @Nullable ToolConnectionMap toolConnectionMap,
      Map<String, Map<Object, Map<String, Object>>> stateStore,
      Optional<String> environmentData,
      Optional<String> tracing) {
    Optional<FunctionDeclaration> declaration = tool.declaration();
    if (declaration.isEmpty()) {
      return Single.just(
          ImmutableMap.of("status", "error", "error_message", "Could not get tool declaration."));
    }

    String prompt =
        fill(
            PROMPT_TEMPLATE,
            ImmutableMap.<String, String>builder()
                .put(
                    "environment_data_snippet",
                    environmentData
                        .map(
                            data ->
                                fill(
                                    ENVIRONMENT_DATA_SNIPPET_TEMPLATE,
                                    ImmutableMap.of("environment_data", data)))
                        .orElse(""))
                .put(
                    "tracing_snippet",
                    tracing
                        .map(
                            trace ->
                                fill(TRACING_SNIPPET_TEMPLATE, ImmutableMap.of("tracing", trace)))
                        .orElse(""))
                .put(
                    "tool_connection_map_json",
                    toolConnectionMap == null ? "''" : toolConnectionMap.toJson())
                .put("state_store_json", JsonBaseModel.toJsonString(stateStore))
                .put("tool_name", tool.name())
                .put("tool_description", tool.description())
                .put("tool_schema_json", declaration.get().toJson())
                .put("tool_arguments_json", JsonBaseModel.toJsonString(args))
                .buildOrThrow());

    return SimulationUtils.generateJson(modelName, modelConfig, prompt)
        .map(responseText -> toMockResponse(tool, responseText, toolConnectionMap, stateStore));
  }

  /**
   * Substitutes every {@code {name}} placeholder in one left-to-right pass. A value is written to
   * the output and never scanned again, so a placeholder that happens to appear inside environment
   * data, a tracing history or a tool's own description stays literal text rather than becoming a
   * substitution site. A placeholder with no value keeps its braces.
   */
  private static String fill(String template, Map<String, String> values) {
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
    StringBuilder filled = new StringBuilder();
    int copiedUpTo = 0;
    while (matcher.find()) {
      String value = values.get(matcher.group(1));
      if (value == null) {
        continue;
      }
      filled.append(template, copiedUpTo, matcher.start()).append(value);
      copiedUpTo = matcher.end();
    }
    return filled.append(template, copiedUpTo, template.length()).toString();
  }

  private static Map<String, Object> toMockResponse(
      BaseTool tool,
      String responseText,
      @Nullable ToolConnectionMap toolConnectionMap,
      Map<String, Map<Object, Map<String, Object>>> stateStore) {
    Map<String, Object> mockResponse;
    try {
      mockResponse =
          JsonBaseModel.getMapper()
              .readValue(
                  SimulationUtils.stripCodeFences(responseText),
                  new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      return ImmutableMap.of(
          "status",
          "error",
          "error_message",
          "Failed to generate valid JSON mock response.",
          "llm_output",
          responseText);
    }
    recordCreatedState(tool, mockResponse, toolConnectionMap, stateStore);
    return mockResponse;
  }

  /**
   * Stores the response under every stateful parameter this tool creates, which is what lets a
   * later call that consumes the same parameter be answered consistently.
   */
  private static void recordCreatedState(
      BaseTool tool,
      Map<String, Object> mockResponse,
      @Nullable ToolConnectionMap toolConnectionMap,
      Map<String, Map<Object, Map<String, Object>>> stateStore) {
    if (toolConnectionMap == null) {
      return;
    }
    for (StatefulParameter parameter : toolConnectionMap.statefulParameters()) {
      if (!parameter.creatingTools().contains(tool.name())) {
        continue;
      }
      Object parameterValue = findValueByKey(mockResponse, parameter.parameterName());
      if (parameterValue != null) {
        stateStore
            .computeIfAbsent(parameter.parameterName(), name -> new ConcurrentHashMap<>())
            .put(parameterValue, mockResponse);
      }
    }
  }

  private static @Nullable Object findValueByKey(Object data, String targetKey) {
    if (data instanceof Map<?, ?> map) {
      if (map.containsKey(targetKey)) {
        return map.get(targetKey);
      }
      for (Object value : map.values()) {
        Object result = findValueByKey(value, targetKey);
        if (result != null) {
          return result;
        }
      }
    } else if (data instanceof List<?> list) {
      for (Object item : list) {
        Object result = findValueByKey(item, targetKey);
        if (result != null) {
          return result;
        }
      }
    }
    return null;
  }
}
