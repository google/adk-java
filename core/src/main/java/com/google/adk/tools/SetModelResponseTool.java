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

package com.google.adk.tools;

import com.google.adk.SchemaUtils;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Internal tool used for output schema workaround.
 *
 * <p>This tool allows the model to set its final response when output_schema is configured
 * alongside other tools. The model should use this tool to provide its final structured response
 * instead of outputting text directly.
 */
public class SetModelResponseTool extends BaseTool {
  public static final String NAME = "set_model_response";

  // Prefix of the SchemaUtils validation message after which the full schema is appended. Used to
  // strip the schema dump from feedback on a best-effort basis; if SchemaUtils changes its wording
  // the feedback simply stays unstripped. runAsync_unknownArg_feedbackOmitsSchemaDump pins the
  // current format.
  private static final String OUTPUT_SCHEMA_DUMP_MARKER = " does not match agent output schema: ";

  private final Schema outputSchema;

  public SetModelResponseTool(Schema outputSchema) {
    super(
        NAME,
        "Set your final response using the required output schema. "
            + "After using any other tools needed to complete the task, always call"
            + " set_model_response with your final answer in the specified schema format.");
    this.outputSchema = outputSchema;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parameters(outputSchema)
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    // Record validated responses on the event actions; return validation feedback so the model can
    // retry.
    return Single.fromCallable(
        () -> {
          try {
            SchemaUtils.validateMapOnSchema(args, outputSchema, /* isInput= */ false);
          } catch (IllegalArgumentException e) {
            return ImmutableMap.of(
                "error",
                "Validation Error found:\n"
                    + sanitizeValidationMessage(e.getMessage())
                    + "\nRecall the set_model_response function correctly, fix the errors, and"
                    + " call it again with all required fields using the correct types.");
          }
          // Match Python's model_dump(exclude_none=True) for Java's map-shaped response.
          Map<String, Object> validatedResponse = excludeNullFields(args);
          toolContext.actions().setSetModelResponse(validatedResponse);
          return validatedResponse;
        });
  }

  private static Map<String, Object> excludeNullFields(Map<String, Object> values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      Object value = entry.getValue();
      if (value != null) {
        result.put(entry.getKey(), excludeNullFields(value));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Object excludeNullFields(Object value) {
    if (value instanceof Map<?, ?>) {
      return excludeNullFields((Map<String, Object>) value);
    }
    if (value instanceof List<?>) {
      List<Object> result = new ArrayList<>();
      for (Object item : (List<?>) value) {
        result.add(excludeNullFields(item));
      }
      return result;
    }
    return value;
  }

  private static String sanitizeValidationMessage(String message) {
    if (message == null) {
      return "Arguments do not match the output schema.";
    }
    // The model already knows the schema from the tool declaration, so the appended schema dump is
    // redundant in feedback.
    int schemaDumpIndex = message.indexOf(OUTPUT_SCHEMA_DUMP_MARKER);
    if (schemaDumpIndex >= 0) {
      message = message.substring(0, schemaDumpIndex) + " does not match agent output schema.";
    }
    return message;
  }
}
