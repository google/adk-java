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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SetModelResponseToolTest {

  @Test
  public void declaration_returnsCorrectFunctionDeclaration() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("field1", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("field1"))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(outputSchema);
    FunctionDeclaration declaration = tool.declaration().get();

    assertThat(declaration.name()).hasValue("set_model_response");
    assertThat(declaration.description()).isPresent();
    assertThat(declaration.description().get()).contains("Set your final response");
    assertThat(declaration.parameters()).hasValue(outputSchema);
  }

  @Test
  public void runAsync_returnsArgs() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("field1", Schema.builder().type("STRING").build()))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(outputSchema);
    Map<String, Object> args = ImmutableMap.of("field1", "value1");

    Map<String, Object> result = tool.runAsync(args, null).blockingGet();

    assertThat(result).isEqualTo(args);
  }

  @Test
  public void runAsync_validatesArgs() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("field1", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("field1"))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(outputSchema);
    Map<String, Object> invalidArgs = ImmutableMap.of("field2", "value2");

    // Should throw validation error
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> tool.runAsync(invalidArgs, null).blockingGet());

    assertThat(exception).hasMessageThat().contains("does not match agent output schema");
  }

  @Test
  public void runAsync_validatesComplexArgs() {
    Schema complexSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "id", Schema.builder().type("INTEGER").build(),
                    "tags",
                        Schema.builder()
                            .type("ARRAY")
                            .items(Schema.builder().type("STRING").build())
                            .build(),
                    "metadata",
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                ImmutableMap.of("key", Schema.builder().type("STRING").build()))
                            .build()))
            .required(ImmutableList.of("id", "tags", "metadata"))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(complexSchema);
    Map<String, Object> complexArgs =
        ImmutableMap.of(
            "id", 123,
            "tags", ImmutableList.of("tag1", "tag2"),
            "metadata", ImmutableMap.of("key", "value"));

    Map<String, Object> result = tool.runAsync(complexArgs, null).blockingGet();

    assertThat(result).containsEntry("id", 123);
    assertThat(result).containsEntry("tags", ImmutableList.of("tag1", "tag2"));
    assertThat(result).containsEntry("metadata", ImmutableMap.of("key", "value"));
  }
}
