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

import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Internal tool used for output schema workaround.
 *
 * <p>This tool allows the model to set its final response when output_schema is configured
 * alongside other tools. The model should use this tool to provide its final structured response
 * instead of outputting text directly.
 */
public class SetModelResponseTool extends BaseTool {
  public static final String NAME = "set_model_response";

  private final Schema outputSchema;

  public SetModelResponseTool(@Nonnull Schema outputSchema) {
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
    // This tool is a marker for the final response, it doesn't do anything but return its arguments
    // which will be captured as the final result.
    return Single.just(args);
  }
}
