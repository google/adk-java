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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Utility class for examples. */
public final class ExampleUtils {

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

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static String convertExamplesToText(List<Example> examples) {
    StringBuilder examplesStr = new StringBuilder();
    for (int exampleNum = 0; exampleNum < examples.size(); exampleNum++) {
      Example example = examples.get(exampleNum);
      StringBuilder output = new StringBuilder();
      output
          .append(String.format(EXAMPLE_START, exampleNum + 1))
          .append(USER_PREFIX)
          .append(example.input().parts().get().get(0).text().get())
          .append("\n\n");

      for (Content content : example.output()) {
        String rolePrefix = content.role().orElse("").equals("model") ? MODEL_PREFIX : USER_PREFIX;
        for (Part part : content.parts().orElse(Collections.emptyList())) {
          if (part.functionCall().isPresent()) {
            Map<String, Object> argsMap =
                part.functionCall().get().args().orElse(Collections.emptyMap());
            List<String> args =
                argsMap.entrySet().stream()
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
                    .collect(Collectors.toList());
            output
                .append(rolePrefix)
                .append(FUNCTION_CALL_PREFIX)
                .append(part.functionCall().get().name().orElse(""))
                .append("(")
                .append(String.join(", ", args))
                .append(")")
                .append(FUNCTION_CALL_SUFFIX);
          } else if (part.functionResponse().isPresent()) {
            try {
              output
                  .append(FUNCTION_RESPONSE_PREFIX)
                  .append(
                      OBJECT_MAPPER.writeValueAsString(
                          part.functionResponse().get().response().orElse(Collections.emptyMap())))
                  .append(FUNCTION_RESPONSE_SUFFIX);
            } catch (JsonProcessingException e) {
              output.append(FUNCTION_RESPONSE_PREFIX).append(FUNCTION_RESPONSE_SUFFIX);
            }
          } else if (part.text().isPresent()) {
            output.append(rolePrefix).append(part.text().orElse("")).append("\n");
          }
        }
      }
      output.append(EXAMPLE_END);
      examplesStr.append(output);
    }

    return EXAMPLES_INTRO + examplesStr.toString() + EXAMPLES_END;
  }

  public static String buildExampleSi(BaseExampleProvider exampleProvider, String query) {
    return convertExamplesToText(exampleProvider.getExamples(query));
  }

  private ExampleUtils() {}
}
