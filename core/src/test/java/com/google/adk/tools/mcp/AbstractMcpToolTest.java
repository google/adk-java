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
import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AbstractMcpToolTest {

  private static final ImageContent IMAGE = ImageContent.builder("aW1hZ2U=", "image/png").build();
  private static final ImmutableMap<String, Object> IMAGE_JSON =
      ImmutableMap.of("type", "image", "data", "aW1hZ2U=", "mimeType", "image/png");

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    // The mapper McpTool uses by default, so tests see the production serialization.
    objectMapper = JsonBaseModel.getMapper();
  }

  @Test
  public void wrapCallResult_textOnly_returnsOnlyTextOutput() {
    CallToolResult result =
        CallToolResult.builder().addTextContent("first").addTextContent("{\"a\":1}").build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map)
        .containsExactly(
            "text_output",
            ImmutableList.of(ImmutableMap.of("text", "first"), ImmutableMap.of("a", 1)));
  }

  @Test
  public void wrapCallResult_mixedContent_keepsTextOutputAndAddsContentAndStructuredContent() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("first")
            .addTextContent("second")
            .addContent(IMAGE)
            .structuredContent(ImmutableMap.of("count", 2))
            .isError(false)
            .build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map)
        .containsExactly(
            "text_output",
            ImmutableList.of(ImmutableMap.of("text", "first"), ImmutableMap.of("text", "second")),
            "content",
            ImmutableList.of(
                ImmutableMap.of("type", "text", "text", "first"),
                ImmutableMap.of("type", "text", "text", "second"),
                IMAGE_JSON),
            "structuredContent",
            ImmutableMap.of("count", 2));
  }

  @Test
  public void wrapCallResult_textWithStructuredContent_keepsTextOutputAndAddsStructuredContent() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("{\"count\":2}")
            .structuredContent(ImmutableMap.of("count", 2))
            .build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map)
        .containsExactly(
            "text_output",
            ImmutableList.of(ImmutableMap.of("count", 2)),
            "structuredContent",
            ImmutableMap.of("count", 2));
  }

  @Test
  public void wrapCallResult_nonTextOnly_returnsContentWithoutError() {
    CallToolResult result = CallToolResult.builder().addContent(IMAGE).build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map).containsExactly("content", ImmutableList.of(IMAGE_JSON));
  }

  @Test
  public void wrapCallResult_emptyContent_returnsEmptyMap() {
    CallToolResult result = CallToolResult.builder().build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map).isEmpty();
  }

  @Test
  public void wrapCallResult_emptyContentWithStructuredContentAndMeta_returnsBoth() {
    CallToolResult result =
        CallToolResult.builder()
            .structuredContent(ImmutableMap.of("count", 2))
            .meta(ImmutableMap.of("trace", "abc"))
            .build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map)
        .containsExactly(
            "structuredContent",
            ImmutableMap.of("count", 2),
            "_meta",
            ImmutableMap.of("trace", "abc"));
  }

  @Test
  public void wrapCallResult_error_returnsOnlyError() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("boom")
            .structuredContent(ImmutableMap.of("count", 2))
            .isError(true)
            .build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map).containsExactly("error", "Tool execution failed. Details: boom");
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
}
