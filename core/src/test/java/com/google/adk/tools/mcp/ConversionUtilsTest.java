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

package com.google.adk.tools.mcp;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ConversionUtilsTest {

  /** Minimal {@link BaseTool} whose declaration is supplied by the test. */
  private static final class FakeTool extends BaseTool {
    private final Optional<FunctionDeclaration> declaration;

    FakeTool(String name, String description, Optional<FunctionDeclaration> declaration) {
      super(name, description);
      this.declaration = declaration;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return declaration;
    }
  }

  /** Backs the {@link FunctionTool} test: ADK derives the tool schema from this signature. */
  public static ImmutableMap<String, Object> getWeather(
      @Annotations.Schema(name = "city", description = "The city name.") String city,
      @Annotations.Schema(name = "count") int count) {
    return ImmutableMap.of("city", city, "count", count);
  }

  private static Map<String, Object> inputSchemaFor(Schema parameters) {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder().name("tool").parameters(parameters).build();
    return ConversionUtils.adkToMcpToolType(
            new FakeTool("tool", "description", Optional.of(declaration)))
        .inputSchema();
  }

  @Test
  public void adkToMcpToolType_declarationWithParameters_setsInputSchema() {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("withParams")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(ImmutableMap.of("city", Schema.builder().type("STRING").build()))
                    .build())
            .build();
    BaseTool tool = new FakeTool("withParams", "has params", Optional.of(declaration));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("withParams");
    assertThat(result.description()).isEqualTo("has params");
    assertThat(result.inputSchema())
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of("city", ImmutableMap.of("type", "string")));
  }

  @Test
  public void adkToMcpToolType_functionTool_emitsJsonSchema() {
    FunctionTool tool = FunctionTool.create(ConversionUtilsTest.class, "getWeather");

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.inputSchema())
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "city", ImmutableMap.of("type", "string", "description", "The city name."),
                "count", ImmutableMap.of("type", "integer")),
            "required",
            ImmutableList.of("city", "count"));
    // The same meta-schema check an MCP SDK server runs on every tool it registers.
    assertThat(McpJsonDefaults.getSchemaValidator().validateSchema(result.inputSchema()).valid())
        .isTrue();
  }

  @Test
  public void adkToMcpToolType_mcpTool_keepsServerInputSchema() {
    ImmutableMap<String, Object> inputSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of("query", ImmutableMap.of("type", "string")),
            "required",
            ImmutableList.of("query"),
            "additionalProperties",
            false);
    McpTool tool =
        new McpTool(
            McpSchema.Tool.builder("search", inputSchema).description("Searches").build(),
            mock(McpSyncClient.class),
            mock(McpSessionManager.class));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("search");
    assertThat(result.inputSchema()).isEqualTo(inputSchema);
  }

  @Test
  public void adkToMcpToolType_parametersAndParametersJsonSchema_prefersParametersJsonSchema() {
    ImmutableMap<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of("jsonParam", ImmutableMap.of("type", "string")));
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("both")
            .parameters(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(
                        ImmutableMap.of(
                            "schemaParam", Schema.builder().type(Type.Known.STRING).build()))
                    .build())
            .parametersJsonSchema(jsonSchema)
            .build();

    McpSchema.Tool result =
        ConversionUtils.adkToMcpToolType(new FakeTool("both", "both", Optional.of(declaration)));

    assertThat(result.inputSchema()).isEqualTo(jsonSchema);
  }

  @Test
  public void adkToMcpToolType_jsonNodeParametersJsonSchema_convertsToMap() {
    ObjectNode jsonSchema = JsonNodeFactory.instance.objectNode().put("type", "object");
    jsonSchema.putObject("properties").putObject("query").put("type", "string");
    FunctionDeclaration declaration =
        FunctionDeclaration.builder().name("node").parametersJsonSchema(jsonSchema).build();

    McpSchema.Tool result =
        ConversionUtils.adkToMcpToolType(new FakeTool("node", "node", Optional.of(declaration)));

    assertThat(result.inputSchema())
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of("query", ImmutableMap.of("type", "string")));
  }

  @Test
  public void adkToMcpToolType_emptyParametersJsonSchema_usesParameters() {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("empty")
            .parameters(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(
                        ImmutableMap.of("city", Schema.builder().type(Type.Known.STRING).build()))
                    .build())
            .parametersJsonSchema(ImmutableMap.of())
            .build();

    McpSchema.Tool result =
        ConversionUtils.adkToMcpToolType(new FakeTool("empty", "empty", Optional.of(declaration)));

    assertThat(result.inputSchema())
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of("city", ImmutableMap.of("type", "string")));
  }

  @Test
  public void adkToMcpToolType_nestedSchemas_lowercasesEveryType() {
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                ImmutableMap.of(
                    "tags",
                    Schema.builder()
                        .type(Type.Known.ARRAY)
                        .items(Schema.builder().type(Type.Known.STRING).build())
                        .build(),
                    "id",
                    Schema.builder()
                        .anyOf(
                            ImmutableList.of(
                                Schema.builder().type(Type.Known.STRING).build(),
                                Schema.builder().type(Type.Known.INTEGER).build()))
                        .build()))
            .build();

    assertThat(inputSchemaFor(schema))
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "tags",
                ImmutableMap.of("type", "array", "items", ImmutableMap.of("type", "string")),
                "id",
                ImmutableMap.of(
                    "anyOf",
                    ImmutableList.of(
                        ImmutableMap.of("type", "string"), ImmutableMap.of("type", "integer")))));
  }

  @Test
  public void adkToMcpToolType_unspecifiedType_isDropped() {
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                ImmutableMap.of(
                    "anything", Schema.builder().type(Type.Known.TYPE_UNSPECIFIED).build()))
            .build();

    assertThat(inputSchemaFor(schema))
        .containsExactly(
            "type", "object", "properties", ImmutableMap.of("anything", ImmutableMap.of()));
  }

  @Test
  public void adkToMcpToolType_everyGenaiKeyword_passesMetaSchemaCheck() {
    // Some keywords sit on a type they do not apply to, such as minLength on an array; they pass
    // through and stay valid JSON Schema.
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .title("Request")
            .description("All keywords.")
            .nullable(true)
            .example(ImmutableMap.of("name", "a"))
            .properties(
                ImmutableMap.of(
                    "name",
                    Schema.builder()
                        .type(Type.Known.STRING)
                        .pattern("^a")
                        .minLength(1L)
                        .maxLength(5L)
                        .format("enum")
                        .enum_(ImmutableList.of("a", "ab"))
                        .default_("a")
                        .minimum(1.0)
                        .build(),
                    "tags",
                    Schema.builder()
                        .type(Type.Known.ARRAY)
                        .items(
                            Schema.builder()
                                .type(Type.Known.NUMBER)
                                .minimum(0.0)
                                .maximum(9.0)
                                .build())
                        .minItems(1L)
                        .maxItems(3L)
                        .minLength(2L)
                        .build(),
                    "id",
                    Schema.builder()
                        .anyOf(
                            ImmutableList.of(
                                Schema.builder().type(Type.Known.STRING).build(),
                                Schema.builder().type(Type.Known.INTEGER).build()))
                        .build()))
            .required(ImmutableList.of("name"))
            .minProperties(1L)
            .maxProperties(2L)
            .propertyOrdering(ImmutableList.of("tags", "name"))
            .build();

    Map<String, Object> inputSchema = inputSchemaFor(schema);

    // The meta-schema check an MCP SDK server runs on every tool it registers.
    assertThat(McpJsonDefaults.getSchemaValidator().validateSchema(inputSchema).valid()).isTrue();
  }

  @Test
  public void adkToMcpToolType_declarationWithoutParameters_defaultsInputSchema() {
    // A present declaration with no parameters is a valid no-argument tool. Before the fix this
    // threw NoSuchElementException from an unguarded Optional.get() on parameters().
    FunctionDeclaration declaration = FunctionDeclaration.builder().name("noParams").build();
    BaseTool tool = new FakeTool("noParams", "no params", Optional.of(declaration));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("noParams");
    assertThat(result.description()).isEqualTo("no params");
    assertThat(result.inputSchema()).containsExactly("type", "object");
  }

  @Test
  public void adkToMcpToolType_noDeclaration_defaultsInputSchema() {
    BaseTool tool = new FakeTool("bare", "no declaration", Optional.empty());

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("bare");
    assertThat(result.description()).isEqualTo("no declaration");
    assertThat(result.inputSchema()).containsExactly("type", "object");
  }
}
