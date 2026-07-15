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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class ClaudeTest {

  private Claude claude;
  private Method partToAnthropicMessageBlockMethod;
  private Method functionDeclarationToAnthropicToolMethod;

  @Before
  public void setUp() throws Exception {
    AnthropicClient mockClient = Mockito.mock(AnthropicClient.class);
    claude = new Claude("claude-3-opus", mockClient);

    // Access private method for testing the extraction logic
    partToAnthropicMessageBlockMethod =
        Claude.class.getDeclaredMethod("partToAnthropicMessageBlock", Part.class);
    partToAnthropicMessageBlockMethod.setAccessible(true);

    functionDeclarationToAnthropicToolMethod =
        Claude.class.getDeclaredMethod(
            "functionDeclarationToAnthropicTool", FunctionDeclaration.class);
    functionDeclarationToAnthropicToolMethod.setAccessible(true);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> inputSchemaProperties(Tool tool) {
    JsonValue properties = (JsonValue) tool.inputSchema()._properties();
    return properties.convert(new TypeReference<Map<String, Object>>() {});
  }

  @Test
  public void testPartToAnthropicMessageBlock_mcpTool_legacyTextOutputKey() throws Exception {
    Map<String, Object> responseData =
        ImmutableMap.of("text_output", ImmutableMap.of("text", "Legacy result text"));
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString())
        .isEqualTo("{\"text_output\":{\"text\":\"Legacy result text\"}}");
  }

  @Test
  public void testPartToAnthropicMessageBlock_jsonFallback() throws Exception {
    Map<String, Object> responseData = ImmutableMap.of("custom_key", "custom_value");
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString()).contains("\"custom_key\":\"custom_value\"");
  }

  @Test
  public void testClaudeUsageMapping_ShouldFailWhenMappingIsMissing() throws Exception {
    long inputTokens = 10L;
    long outputTokens = 20L;
    Usage mockUsage = mock(Usage.class);
    when(mockUsage.inputTokens()).thenReturn(inputTokens);
    when(mockUsage.outputTokens()).thenReturn(outputTokens);

    Message mockMessage = mock(Message.class);
    when(mockMessage.usage()).thenReturn(mockUsage);
    when(mockMessage.content()).thenReturn(Collections.emptyList());

    Method convertMethod =
        Claude.class.getDeclaredMethod("convertAnthropicResponseToLlmResponse", Message.class);
    convertMethod.setAccessible(true);
    LlmResponse result = (LlmResponse) convertMethod.invoke(claude, mockMessage);
    assertTrue(result.usageMetadata().isPresent());
    assertEquals(inputTokens, (long) result.usageMetadata().get().promptTokenCount().orElse(0));
    assertEquals(
        outputTokens, (long) result.usageMetadata().get().candidatesTokenCount().orElse(0));
  }

  @Test
  public void functionDeclarationToAnthropicTool_usesParameters() throws Exception {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("retrievesItemByItemNumber")
            .description("Retrieves an item")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(
                        ImmutableMap.of("itemNumber", Schema.builder().type("STRING").build()))
                    .required(ImmutableList.of("itemNumber"))
                    .build())
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).containsKey("itemNumber");
    // The genai type "STRING" is lowercased to the JSON Schema "string" for Claude.
    assertThat(((Map<String, Object>) properties.get("itemNumber")).get("type"))
        .isEqualTo("string");
    assertThat(tool.inputSchema().required()).hasValue(ImmutableList.of("itemNumber"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_fallsBackToParametersJsonSchema()
      throws Exception {
    // MCP tools populate parametersJsonSchema instead of the structured parameters() field.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "dataset",
                ImmutableMap.of("type", "string", "description", "The dataset id"),
                "project",
                ImmutableMap.of("type", "string")),
            "required",
            ImmutableList.of("dataset"));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("get_dataset_info")
            .description("Gets dataset info")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    // Before the fix these properties were empty, so Claude could not invoke the MCP tool.
    assertThat(properties).containsKey("dataset");
    assertThat(properties).containsKey("project");
    assertThat(((Map<String, Object>) properties.get("dataset")).get("type")).isEqualTo("string");
    assertThat(tool.inputSchema().required()).hasValue(ImmutableList.of("dataset"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_noParameters_hasEmptyProperties()
      throws Exception {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder().name("no_args_tool").description("Takes no args").build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).isEmpty();
    assertThat(tool.inputSchema().required()).isEmpty();
  }

  @Test
  public void functionDeclarationToAnthropicTool_unionTypeArray_doesNotThrow() throws Exception {
    // JSON Schema permits a union type array (e.g. ["string", "null"]), which MCP tools can emit.
    // It must not be cast to String, which previously threw ClassCastException.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "nickname", ImmutableMap.of("type", ImmutableList.of("string", "null"))));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("set_nickname")
            .description("Sets a nickname")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).containsKey("nickname");
    // The union type array is preserved rather than crashing on the String cast.
    assertThat(((Map<String, Object>) properties.get("nickname")).get("type"))
        .isEqualTo(ImmutableList.of("string", "null"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_preservesRefsAndDefs() throws Exception {
    // MCP tools may use $ref/$defs. These top-level keywords must survive so Claude does not
    // receive dangling references.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of("pet", ImmutableMap.of("$ref", "#/$defs/Pet")),
            "$defs",
            ImmutableMap.of(
                "Pet",
                ImmutableMap.of(
                    "type",
                    "object",
                    "properties",
                    ImmutableMap.of("name", ImmutableMap.of("type", "string")))));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("register_pet")
            .description("Registers a pet")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    // The $ref is kept on the property...
    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(((Map<String, Object>) properties.get("pet")).get("$ref")).isEqualTo("#/$defs/Pet");
    // ...and the $defs block survives as a top-level keyword.
    JsonValue defsValue = (JsonValue) tool.inputSchema()._additionalProperties().get("$defs");
    assertThat(defsValue).isNotNull();
    Map<String, Object> defs = defsValue.convert(new TypeReference<Map<String, Object>>() {});
    assertThat(defs).containsKey("Pet");
  }
}
