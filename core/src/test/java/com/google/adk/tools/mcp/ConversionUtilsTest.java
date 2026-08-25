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

import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
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

  @Test
  public void adkToMcpToolType_declarationWithParameters_setsInputSchema() {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("withParams")
            .parameters(Schema.builder().type("OBJECT").build())
            .build();
    BaseTool tool = new FakeTool("withParams", "has params", Optional.of(declaration));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("withParams");
    assertThat(result.description()).isEqualTo("has params");
    assertThat(result.inputSchema()).isNotNull();
    assertThat(result.inputSchema()).containsEntry("type", "OBJECT");
  }

  @Test
  public void adkToMcpToolType_declarationWithoutParameters_omitsInputSchema() {
    // A present declaration with no parameters is a valid no-argument tool. Before the fix this
    // threw NoSuchElementException from an unguarded Optional.get() on parameters().
    FunctionDeclaration declaration = FunctionDeclaration.builder().name("noParams").build();
    BaseTool tool = new FakeTool("noParams", "no params", Optional.of(declaration));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("noParams");
    assertThat(result.description()).isEqualTo("no params");
    assertThat(result.inputSchema()).isNotNull();
    assertThat(result.inputSchema()).isEqualTo(Map.of());
  }

  @Test
  public void adkToMcpToolType_declarationWithParametersJsonSchema_preservesInputSchema() {
    Map<String, Object> jsonSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("city", Map.of("type", "string", "description", "the city")),
            "required",
            java.util.List.of("city"));
    FunctionDeclaration declaration =
        FunctionDeclaration.builder().name("weatherTool").parametersJsonSchema(jsonSchema).build();
    BaseTool tool = new FakeTool("weatherTool", "weather lookup", Optional.of(declaration));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("weatherTool");
    assertThat(result.description()).isEqualTo("weather lookup");
    assertThat(result.inputSchema()).isEqualTo(jsonSchema);
  }

  @Test
  public void adkToMcpToolType_noDeclaration_omitsInputSchema() {
    BaseTool tool = new FakeTool("bare", "no declaration", Optional.empty());

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("bare");
    assertThat(result.description()).isEqualTo("no declaration");
    assertThat(result.inputSchema()).isNotNull();
    assertThat(result.inputSchema()).isEqualTo(Map.of());
  }
}
