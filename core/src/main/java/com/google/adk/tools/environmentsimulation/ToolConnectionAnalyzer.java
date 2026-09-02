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

import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks a model which parameters the tools share, so that a mock response for one tool can stay
 * consistent with the mock responses of the tools around it. For example, {@code get_ticket}
 * consumes a {@code ticket_id} that {@code create_ticket} produced.
 */
final class ToolConnectionAnalyzer {

  private static final Logger logger = LoggerFactory.getLogger(ToolConnectionAnalyzer.class);

  private static final String PROMPT_TEMPLATE =
      """
      You are an expert software architect analyzing a set of tools to understand
      stateful dependencies. Your task is to identify parameters that act as
      stateful identifiers (like IDs) and classify the tools that interact with
      them.

      **Definitions:**
      - A **"creating tool"** is a tool that creates a new resource or makes a
        significant state change to an existing one (e.g., creating, updating,
        canceling, or deleting). Tool names like `create_account`, `cancel_order`,
        or `update_price` are strong indicators. These tools are responsible for
        generating or modifying the state associated with an ID.
      - A **"consuming tool"** is a tool that uses a resource's ID to retrieve
        information without changing its state. Tool names like `get_user`,
        `list_events`, or `find_order` are strong indicators.

      **Your Goal:**
      Analyze the following tool schemas and identify the shared, stateful
      parameters (like `user_id`, `order_id`, etc.).

      For each stateful parameter you identify, classify the tools into
      `creating_tools` and `consuming_tools` based on the definitions above.

      **Example:** A `create_ticket` tool would be a `creating_tool` for
      `ticket_id`. A `get_ticket` tool would be a `consuming_tool` for
      `ticket_id`. A `list_tickets` tool that takes a `user_id` as input is a
      `consuming_tool` for `user_id`.

      **Analyze the following tool schemas:**
      {tool_schemas_json}

      **Output Format:**
      Generate a JSON object with a single key, "stateful_parameters", which is a
      list. Each item in the list must have these keys:
      - "parameter_name": The name of the shared parameter (e.g., "ticket_id").
      - "creating_tools": A list of tools that create or modify this parameter's
        state.
      - "consuming_tools": A list of tools that use this parameter as input for
        read-only operations.

      ONLY return the raw JSON object.
      Your response must start with '{' and end with '}'.
      """;

  private final String modelName;
  private final GenerateContentConfig modelConfig;

  ToolConnectionAnalyzer(String modelName, GenerateContentConfig modelConfig) {
    this.modelName = modelName;
    this.modelConfig = modelConfig;
  }

  /** Analyzes the given tools and returns the map of their connections. */
  Single<ToolConnectionMap> analyze(List<BaseTool> tools) {
    String toolSchemasJson =
        tools.stream()
            .map(BaseTool::declaration)
            .flatMap(Optional::stream)
            .map(FunctionDeclaration::toJson)
            .collect(joining(",\n", "[\n", "\n]"));
    String prompt = PROMPT_TEMPLATE.replace("{tool_schemas_json}", toolSchemasJson);
    return SimulationUtils.generateJson(modelName, modelConfig, prompt).map(this::parse);
  }

  private ToolConnectionMap parse(String responseText) {
    try {
      return JsonBaseModel.getMapper()
          .readValue(SimulationUtils.stripCodeFences(responseText), ToolConnectionMap.class);
    } catch (JsonProcessingException e) {
      logger.warn(
          "Failed to read a tool connection analysis from the model. Proceeding without a"
              + " connection map. Model output:\n{}",
          responseText,
          e);
      return ToolConnectionMap.builder().build();
    }
  }
}
