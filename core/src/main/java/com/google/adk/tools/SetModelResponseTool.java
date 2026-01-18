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

package com.google.adk.tools;

import com.google.adk.SchemaUtils;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;

/** Tool for setting model response when using output_schema with other tools. */
public class SetModelResponseTool extends BaseTool {

  private final Schema outputSchema;

  public SetModelResponseTool(Schema outputSchema) {
    super(
        "set_model_response",
        "Set your final response using the required output schema. Use this tool to provide your"
            + " final structured answer instead of outputting text directly.");
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
    try {
      SchemaUtils.validateMapOnSchema(args, outputSchema, /* isInput= */ false);
    } catch (IllegalArgumentException e) {
      return Single.error(e);
    }
    return Single.just(args);
  }
}
