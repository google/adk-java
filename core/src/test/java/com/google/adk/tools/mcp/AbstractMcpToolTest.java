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
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.FunctionDeclaration;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AbstractMcpToolTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testWrapCallResult_success() {
    CallToolResult result =
        CallToolResult.builder()
            .content(ImmutableList.of(new TextContent("success")))
            .isError(false)
            .build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map).containsKey("text_output");
    List<?> content = (List<?>) map.get("text_output");
    assertThat(content).hasSize(1);

    Map<?, ?> contentItem = (Map<?, ?>) content.get(0);
    assertThat(contentItem).containsEntry("text", "success");
  }

  @Test
  public void instantiateWithToolBuilder_nullDescription_succeeds() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    McpSessionManager managerMock = mock(McpSessionManager.class);
    McpSchema.Tool schemaTool = McpSchema.Tool.builder().name("realTool").build();

    McpTool tool = new McpTool(schemaTool, sessionMock, managerMock, objectMapper);

    assertEquals("", tool.description());
    assertEquals("realTool", tool.name());
  }

  @Test
  public void declaration_withMcp2MapSchema_createsFunctionDeclarationSuccessfully() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    McpSessionManager managerMock = mock(McpSessionManager.class);

    Map<String, Object> schemaMap =
        Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string")));

    McpSchema.Tool schemaTool =
        McpSchema.Tool.builder()
            .name("weatherTool")
            .description("Fetches weather")
            .inputSchema(schemaMap)
            .build();

    McpTool tool = new McpTool(schemaTool, sessionMock, managerMock, objectMapper);

    Optional<FunctionDeclaration> declarationOpt = tool.declaration();
    assertThat(declarationOpt).isPresent();
    FunctionDeclaration declaration = declarationOpt.get();

    assertThat(declaration.name()).hasValue("weatherTool");
    assertThat(declaration.description()).hasValue("Fetches weather");
    assertThat(declaration.parametersJsonSchema()).isPresent();
    assertThat(declaration.parametersJsonSchema().get()).isEqualTo(schemaMap);
  }

  @Test
  public void declaration_withOutputSchema_setsResponseJsonSchema() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    McpSessionManager managerMock = mock(McpSessionManager.class);

    Map<String, Object> inputSchema = Map.of("type", "object");
    Map<String, Object> outputSchema =
        Map.of("type", "object", "properties", Map.of("status", Map.of("type", "string")));

    McpSchema.Tool schemaTool =
        McpSchema.Tool.builder()
            .name("outputTool")
            .description("Tool with output schema")
            .inputSchema(inputSchema)
            .outputSchema(outputSchema)
            .build();

    McpTool tool = new McpTool(schemaTool, sessionMock, managerMock, objectMapper);

    Optional<FunctionDeclaration> declarationOpt = tool.declaration();
    assertThat(declarationOpt).isPresent();
    FunctionDeclaration declaration = declarationOpt.get();

    assertThat(declaration.responseJsonSchema()).isPresent();
    assertThat(declaration.responseJsonSchema().get()).isEqualTo(outputSchema);
  }
}
