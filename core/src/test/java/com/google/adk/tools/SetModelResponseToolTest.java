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

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SetModelResponseToolTest {

  private static ToolContext createToolContext() {
    return ToolContext.builder(
            createInvocationContext(createTestAgent(createTestLlm(LlmResponse.builder().build()))))
        .build();
  }

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
  public void runAsync_returnsArgsAndRecordsValidatedResponse() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("field1", Schema.builder().type("STRING").build()))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(outputSchema);
    ToolContext toolContext = createToolContext();
    Map<String, Object> args = ImmutableMap.of("field1", "value1");

    Map<String, Object> result = tool.runAsync(args, toolContext).blockingGet();

    assertThat(result).isEqualTo(args);
    assertThat(toolContext.actions().setModelResponse()).hasValue(args);
  }

  @Test
  public void runAsync_invalidArgs_returnsValidationFeedback() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("field1", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("field1"))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(outputSchema);
    ToolContext toolContext = createToolContext();
    Map<String, Object> invalidArgs = ImmutableMap.of();

    Map<String, Object> result = tool.runAsync(invalidArgs, toolContext).blockingGet();

    assertThat(result).containsKey("error");
    assertThat((String) result.get("error")).contains("field1");
    assertThat(toolContext.actions().setModelResponse()).isEmpty();
  }

  @Test
  public void runAsync_nullableNull_excludesNullFromReturnedAndRecordedResponse() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "a",
                    Schema.builder().type("STRING").build(),
                    "b",
                    Schema.builder().type("STRING").nullable(true).build()))
            .required(ImmutableList.of("a"))
            .build();
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("a", "x");
    args.put("b", null);

    ToolContext toolContext = createToolContext();
    Map<String, Object> result =
        new SetModelResponseTool(outputSchema).runAsync(args, toolContext).blockingGet();

    assertThat(result).containsExactly("a", "x");
    assertThat(toolContext.actions().setModelResponse()).hasValue(ImmutableMap.of("a", "x"));
  }

  @Test
  public void runAsync_nestedNullableNull_excludesNullFromReturnedAndRecordedResponse() {
    Schema addressSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "city",
                    Schema.builder().type("STRING").build(),
                    "zip",
                    Schema.builder().type("STRING").nullable(true).build()))
            .required(ImmutableList.of("city"))
            .build();
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "name", Schema.builder().type("STRING").build(), "addr", addressSchema))
            .required(ImmutableList.of("name", "addr"))
            .build();
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("city", "NYC");
    address.put("zip", null);

    Map<String, Object> args = ImmutableMap.of("name", "x", "addr", address);

    ToolContext toolContext = createToolContext();
    Map<String, Object> result =
        new SetModelResponseTool(outputSchema).runAsync(args, toolContext).blockingGet();

    Map<String, Object> expected =
        ImmutableMap.of("name", "x", "addr", ImmutableMap.of("city", "NYC"));
    assertThat(result).isEqualTo(expected);
    assertThat(toolContext.actions().setModelResponse()).hasValue(expected);
  }

  @Test
  public void runAsync_arrayOfObjects_excludesNestedNullFromReturnedAndRecordedResponse() {
    Schema addressSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "city",
                    Schema.builder().type("STRING").build(),
                    "zip",
                    Schema.builder().type("STRING").nullable(true).build()))
            .required(ImmutableList.of("city"))
            .build();
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "addresses", Schema.builder().type("ARRAY").items(addressSchema).build()))
            .required(ImmutableList.of("addresses"))
            .build();
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("city", "NYC");
    address.put("zip", null);
    Map<String, Object> args = ImmutableMap.of("addresses", ImmutableList.of(address));

    ToolContext toolContext = createToolContext();
    Map<String, Object> result =
        new SetModelResponseTool(outputSchema).runAsync(args, toolContext).blockingGet();

    Map<String, Object> expected =
        ImmutableMap.of("addresses", ImmutableList.of(ImmutableMap.of("city", "NYC")));
    assertThat(result).isEqualTo(expected);
    assertThat(toolContext.actions().setModelResponse()).hasValue(expected);
  }

  @Test
  public void runAsync_emptyArrayWithoutItems_returnsAndRecordsEmptyArray() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("tags", Schema.builder().type("ARRAY").build()))
            .required(ImmutableList.of("tags"))
            .build();
    Map<String, Object> args = ImmutableMap.of("tags", ImmutableList.of());

    ToolContext toolContext = createToolContext();
    Map<String, Object> result =
        new SetModelResponseTool(outputSchema).runAsync(args, toolContext).blockingGet();

    assertThat(result).isEqualTo(args);
    assertThat(toolContext.actions().setModelResponse()).hasValue(args);
  }

  @Test
  public void runAsync_arrayWithNullElement_preservesNullInReturnedAndRecordedResponse() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "tags",
                    Schema.builder()
                        .type("ARRAY")
                        .items(Schema.builder().type("STRING").nullable(true).build())
                        .build()))
            .required(ImmutableList.of("tags"))
            .build();
    Map<String, Object> args = ImmutableMap.of("tags", Arrays.asList("first", null, "last"));

    ToolContext toolContext = createToolContext();
    Map<String, Object> result =
        new SetModelResponseTool(outputSchema).runAsync(args, toolContext).blockingGet();

    assertThat(result).isEqualTo(args);
    assertThat(toolContext.actions().setModelResponse()).hasValue(args);
  }

  @Test
  public void runAsync_unknownArg_feedbackOmitsSchemaDump() {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("field1", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("field1"))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(outputSchema);
    Map<String, Object> invalidArgs = ImmutableMap.of("field2", "value2");

    Map<String, Object> result = tool.runAsync(invalidArgs, createToolContext()).blockingGet();

    String error = (String) result.get("error");
    assertThat(error).contains("field2");
    assertThat(error).contains("does not match agent output schema");
    assertThat(error).doesNotContain(outputSchema.toString());
  }

  @Test
  public void runAsync_validatesComplexArgs() {
    Schema complexSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "id",
                    Schema.builder().type("INTEGER").build(),
                    "tags",
                    Schema.builder()
                        .type("ARRAY")
                        .items(Schema.builder().type("STRING").build())
                        .build(),
                    "metadata",
                    Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of("key", Schema.builder().type("STRING").build()))
                        .build()))
            .required(ImmutableList.of("id", "tags", "metadata"))
            .build();

    SetModelResponseTool tool = new SetModelResponseTool(complexSchema);
    ToolContext toolContext = createToolContext();
    Map<String, Object> complexArgs =
        ImmutableMap.of(
            "id", 123,
            "tags", ImmutableList.of("tag1", "tag2"),
            "metadata", ImmutableMap.of("key", "value"));

    Map<String, Object> result = tool.runAsync(complexArgs, toolContext).blockingGet();

    assertThat(result).containsEntry("id", 123);
    assertThat(result).containsEntry("tags", ImmutableList.of("tag1", "tag2"));
    assertThat(result).containsEntry("metadata", ImmutableMap.of("key", "value"));
    assertThat(toolContext.actions().setModelResponse()).hasValue(complexArgs);
  }
}
