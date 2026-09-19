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
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
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

    assertThat(map).containsEntry("isError", false);
    assertThat(map).containsKey("text_output");
    List<?> textOutput = (List<?>) map.get("text_output");
    assertThat(textOutput).hasSize(1);

    Map<?, ?> contentItem = (Map<?, ?>) textOutput.get(0);
    assertThat(contentItem).containsEntry("text", "success");
  }

  @Test
  public void testWrapCallResult_mixedContent_success() {
    CallToolResult result =
        new CallToolResult(
            ImmutableList.of(
                new TextContent("first"), new ImageContent(null, "aW1hZ2U=", "image/png", null)),
            false,
            Map.of("count", 2),
            Map.of("traceId", "trace-123"));

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map).containsEntry("isError", false);
    assertThat(map).containsEntry("structuredContent", Map.of("count", 2));
    assertThat(map).containsEntry("_meta", Map.of("traceId", "trace-123"));

    List<?> content = (List<?>) map.get("content");
    assertThat(content).hasSize(2);
    Map<?, ?> textContent = (Map<?, ?>) content.get(0);
    assertThat(textContent).containsEntry("type", "text");
    assertThat(textContent).containsEntry("text", "first");
    Map<?, ?> imageContent = (Map<?, ?>) content.get(1);
    assertThat(imageContent).containsEntry("type", "image");
    assertThat(imageContent).containsEntry("data", "aW1hZ2U=");
    assertThat(imageContent).containsEntry("mimeType", "image/png");

    List<?> textOutput = (List<?>) map.get("text_output");
    assertThat(textOutput).containsExactly(Map.of("text", "first"));
  }

  @Test
  public void testWrapCallResult_nonTextContent_success() {
    CallToolResult result =
        new CallToolResult(
            ImmutableList.of(new ImageContent(null, "aW1hZ2U=", "image/png", null)),
            false,
            null,
            null);

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map).doesNotContainKey("error");
    assertThat(map).containsEntry("isError", false);
    assertThat((List<?>) map.get("content")).hasSize(1);
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
