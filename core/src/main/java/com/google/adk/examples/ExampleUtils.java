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

package com.google.adk.examples;

import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for examples. */
public final class ExampleUtils {

  private static final Logger logger = LoggerFactory.getLogger(ExampleUtils.class);

  // Constant parts of the example string
  private static final String EXAMPLES_INTRO =
      "<EXAMPLES>\nBegin few-shot\nThe following are examples of user queries and"
          + " model responses using the available tools.\n\n";
  private static final String EXAMPLES_END =
      "End few-shot\nNow, try to follow these examples and complete the following"
          + " conversation\n<EXAMPLES>";

  @SuppressWarnings("InlineFormatString")
  private static final String EXAMPLE_START = "EXAMPLE %d:\nBegin example\n";

  private static final String EXAMPLE_END = "End example\n\n";
  private static final String USER_PREFIX = "[user]\n";
  private static final String MODEL_PREFIX = "[model]\n";
  private static final String FUNCTION_CALL_PREFIX = "```tool_code\n";
  private static final String FUNCTION_CALL_SUFFIX = "\n```\n";
  private static final String FUNCTION_RESPONSE_PREFIX = "```tool_outputs\n";
  private static final String FUNCTION_RESPONSE_SUFFIX = "\n```\n";

  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  /**
   * Converts a list of examples into a formatted few-shot prompt string.
   *
   * @param examples List of examples.
   * @return string representation of the examples block.
   */
  private static String convertExamplesToText(List<Example> examples) {
    StringBuilder examplesStr = new StringBuilder();

    // super header
    examplesStr.append(EXAMPLES_INTRO);

    for (int i = 0; i < examples.size(); i++) {
      Example example = examples.get(i);

      // header
      examplesStr.append(String.format(EXAMPLE_START, i + 1));

      // user content
      appendInput(example, examplesStr);

      // model content
      for (Content content : example.output()) {
        appendOutput(content, examplesStr);
      }

      // footer
      examplesStr.append(EXAMPLE_END);
    }

    // super footer
    examplesStr.append(EXAMPLES_END);

    return examplesStr.toString();
  }

  private static void appendInput(Example example, StringBuilder builder) {
    example
        .input()
        .parts()
        .flatMap(parts -> parts.stream().findFirst().flatMap(Part::text))
        .ifPresent(text -> builder.append(USER_PREFIX).append(text).append("\n\n"));
  }

  private static void appendOutput(Content output, StringBuilder builder) {
    String rolePrefix = output.role().orElse("").equals("model") ? MODEL_PREFIX : USER_PREFIX;
    for (Part part : output.parts().orElse(ImmutableList.of())) {
      if (part.functionCall().isPresent()) {
        appendFunctionCall(part.functionCall().get(), rolePrefix, builder);
      } else if (part.functionResponse().isPresent()) {
        appendFunctionResponse(part.functionResponse().get(), builder);
      } else if (part.text().isPresent()) {
        builder.append(rolePrefix).append(part.text().get()).append("\n");
      }
    }
  }

  private static void appendFunctionCall(
      FunctionCall functionCall, String rolePrefix, StringBuilder builder) {
    String argsString =
        functionCall.args().stream()
            .flatMap(argsMap -> argsMap.entrySet().stream())
            .map(
                entry -> {
                  String key = entry.getKey();
                  Object value = entry.getValue();
                  if (value instanceof String) {
                    return String.format("%s='%s'", key, value);
                  } else {
                    return String.format("%s=%s", key, value);
                  }
                })
            .collect(joining(", "));
    builder
        .append(rolePrefix)
        .append(FUNCTION_CALL_PREFIX)
        .append(functionCall.name().orElse(""))
        .append("(")
        .append(argsString)
        .append(")")
        .append(FUNCTION_CALL_SUFFIX);
  }

  private static void appendFunctionResponse(FunctionResponse response, StringBuilder builder) {
    try {
      Object responseMap = response.response().orElse(ImmutableMap.of());
      builder
          .append(FUNCTION_RESPONSE_PREFIX)
          .append(objectMapper.writeValueAsString(responseMap))
          .append(FUNCTION_RESPONSE_SUFFIX);
    } catch (JsonProcessingException e) {
      logger.error("Failed to serialize function response", e);
      builder.append(FUNCTION_RESPONSE_PREFIX).append(FUNCTION_RESPONSE_SUFFIX);
    }
  }

  /**
   * Builds a formatted few-shot example string for the given query. The string can be used for
   * system instructions (i.e., the method name means "Build Example System Instructions").
   *
   * @param exampleProvider Source of examples.
   * @param query User query.
   * @return formatted string with few-shot examples.
   */
  public static String buildExampleSi(BaseExampleProvider exampleProvider, String query) {
    return convertExamplesToText(exampleProvider.getExamples(query));
  }

  private ExampleUtils() {}
}
