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
import io.modelcontextprotocol.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AbstractMcpToolTest {

  // Base64 of the 5 bytes "image".
  private static final ImageContent IMAGE = ImageContent.builder("aW1hZ2U=", "image/png").build();
  private static final ImmutableMap<String, Object> IMAGE_JSON =
      ImmutableMap.of("type", "image", "mimeType", "image/png", "size", 5);

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

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map)
        .containsExactly(
            "text_output",
            ImmutableList.of(ImmutableMap.of("text", "first"), ImmutableMap.of("a", 1)));
  }

  @Test
  public void wrapCallResult_mixedContentWithPropagation_returnsTextContentAndStructuredContent() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("first")
            .addTextContent("second")
            .addContent(IMAGE)
            .structuredContent(ImmutableMap.of("count", 2))
            .isError(false)
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ true);

    assertThat(map)
        .containsExactly(
            "text_output",
            ImmutableList.of(ImmutableMap.of("text", "first"), ImmutableMap.of("text", "second")),
            "content",
            ImmutableList.of(IMAGE_JSON),
            "structuredContent",
            ImmutableMap.of("count", 2));
  }

  @Test
  public void wrapCallResult_withoutPropagation_omitsStructuredContent() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("Found 2 items")
            .structuredContent(ImmutableMap.of("count", 2))
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map)
        .containsExactly("text_output", ImmutableList.of(ImmutableMap.of("text", "Found 2 items")));
  }

  @Test
  public void wrapCallResult_withPropagation_addsStructuredContentEvenWhenMirrored() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("{\"count\": 2}")
            .structuredContent(ImmutableMap.of("count", 2))
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ true);

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

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map).containsExactly("content", ImmutableList.of(IMAGE_JSON));
  }

  @Test
  public void wrapCallResult_audioContent_replacesDataWithSize() {
    CallToolResult result =
        CallToolResult.builder()
            .addContent(AudioContent.builder("AAECAw==", "audio/wav").build())
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map)
        .containsExactly(
            "content",
            ImmutableList.of(ImmutableMap.of("type", "audio", "mimeType", "audio/wav", "size", 4)));
  }

  @Test
  public void wrapCallResult_base64WithLineBreaksAndNoPadding_reportsDecodedSize() {
    // 7 bytes: 10 base64 characters without padding, split across lines.
    CallToolResult result =
        CallToolResult.builder()
            .addContent(ImageContent.builder("AAECAwQF\nBg", "image/png").build())
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map)
        .containsExactly(
            "content",
            ImmutableList.of(ImmutableMap.of("type", "image", "mimeType", "image/png", "size", 7)));
  }

  @Test
  public void wrapCallResult_nullContentItem_isSkipped() {
    List<Content> contents = new ArrayList<>();
    contents.add(TextContent.builder("ok").build());
    contents.add(null);
    CallToolResult result = CallToolResult.builder().content(contents).build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map).containsExactly("text_output", ImmutableList.of(ImmutableMap.of("text", "ok")));
  }

  @Test
  public void wrapCallResult_embeddedBlobResource_replacesBlobWithSize() {
    CallToolResult result =
        CallToolResult.builder()
            .addContent(
                EmbeddedResource.builder(
                        BlobResourceContents.builder("file:///a.bin", "AAEC")
                            .mimeType("application/octet-stream")
                            .build())
                    .build())
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map)
        .containsExactly(
            "content",
            ImmutableList.of(
                ImmutableMap.of(
                    "type",
                    "resource",
                    "resource",
                    ImmutableMap.of(
                        "uri",
                        "file:///a.bin",
                        "mimeType",
                        "application/octet-stream",
                        "size",
                        3))));
  }

  @Test
  public void wrapCallResult_embeddedTextResource_keepsText() {
    CallToolResult result =
        CallToolResult.builder()
            .addContent(
                EmbeddedResource.builder(
                        TextResourceContents.builder("file:///a.txt", "hello")
                            .mimeType("text/plain")
                            .build())
                    .build())
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map)
        .containsExactly(
            "content",
            ImmutableList.of(
                ImmutableMap.of(
                    "type",
                    "resource",
                    "resource",
                    ImmutableMap.of(
                        "uri", "file:///a.txt", "mimeType", "text/plain", "text", "hello"))));
  }

  @Test
  public void wrapCallResult_emptyContent_returnsEmptyMap() {
    CallToolResult result = CallToolResult.builder().build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map).isEmpty();
  }

  @Test
  public void wrapCallResult_emptyContentWithPropagation_returnsStructuredContent() {
    CallToolResult result =
        CallToolResult.builder().structuredContent(ImmutableMap.of("count", 2)).build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ true);

    assertThat(map).containsExactly("structuredContent", ImmutableMap.of("count", 2));
  }

  @Test
  public void wrapCallResult_resultMeta_isLeftOut() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("ok")
            .meta(ImmutableMap.of("ui", ImmutableMap.of("resourceUri", "ui://widget")))
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ false);

    assertThat(map).containsExactly("text_output", ImmutableList.of(ImmutableMap.of("text", "ok")));
  }

  @Test
  public void wrapCallResult_error_returnsOnlyError() {
    CallToolResult result =
        CallToolResult.builder()
            .addTextContent("boom")
            .structuredContent(ImmutableMap.of("count", 2))
            .isError(true)
            .build();

    Map<String, Object> map = wrap(result, /* propagateStructuredContent= */ true);

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

  private Map<String, Object> wrap(CallToolResult result, boolean propagateStructuredContent) {
    return AbstractMcpTool.wrapCallResult(objectMapper, result, propagateStructuredContent);
  }
}
