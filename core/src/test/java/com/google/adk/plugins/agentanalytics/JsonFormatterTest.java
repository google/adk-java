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

package com.google.adk.plugins.agentanalytics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JsonFormatterTest {

  @Test
  public void parse_llmRequest_populatesPrompt() throws Exception {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.fromParts(Part.fromText("hello")).toBuilder().role("user").build()))
            .build();

    Parser.ParsedContent result =
        new Parser(null, 100, null, true).parse(request, "trace", "span").get();

    assertTrue(result.content().has("prompt"));
    ArrayNode prompt = (ArrayNode) result.content().get("prompt");
    assertEquals(1, prompt.size());
    assertEquals("user", prompt.get(0).get("role").asText());
    assertEquals("hello", prompt.get(0).get("content").asText());
    assertFalse(result.isTruncated());
  }

  @Test
  public void parse_llmRequest_populatesSystemPrompt() throws Exception {
    LlmRequest request =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText("be helpful")))
                    .build())
            .build();

    Parser.ParsedContent result =
        new Parser(null, 100, null, true).parse(request, "trace", "span").get();

    assertTrue(result.content().has("system_prompt"));
    assertEquals("be helpful", result.content().get("system_prompt").asText());
    assertFalse(result.isTruncated());
  }

  @Test
  public void parse_string_truncates() throws Exception {
    String longString = "this is a very long string that should be truncated";
    Parser.ParsedContent result =
        new Parser(null, 24, null, true).parse(longString, "trace", "span").get();

    assertTrue(result.isTruncated());
    assertEquals("this is a ...[truncated]", result.content().asText());
  }

  @Test
  public void parse_map_truncatesNested() throws Exception {
    ImmutableMap<String, Object> map =
        ImmutableMap.of("key", "this is a very long value that should definitely be truncated");
    Parser.ParsedContent result =
        new Parser(null, 24, null, true).parse(map, "trace", "span").get();

    assertTrue(result.isTruncated());
    assertEquals("this is a ...[truncated]", result.content().get("key").asText());
  }

  @Test
  public void parse_content_returnsSummary() throws Exception {
    Content content = Content.fromParts(Part.fromText("part 1"), Part.fromText("part 2"));
    Parser.ParsedContent result =
        new Parser(null, 100, null, true).parse(content, "trace", "span").get();

    assertEquals("part 1 | part 2", result.content().get("text_summary").asText());
    assertEquals(2, result.parts().size());
  }

  @Test
  public void parse_content_withFileData() throws Exception {
    FileData fileData =
        FileData.builder().fileUri("gs://bucket/file.txt").mimeType("text/plain").build();
    Content content = Content.fromParts(Part.builder().fileData(fileData).build());
    Parser.ParsedContent result =
        new Parser(null, 100, null, true).parse(content, "trace", "span").get();

    assertEquals(1, result.parts().size());
    JsonNode partData = result.parts().get(0);
    assertEquals("EXTERNAL_URI", partData.get("storage_mode").asText());
    assertEquals("gs://bucket/file.txt", partData.get("uri").asText());
    assertEquals("text/plain", partData.get("mime_type").asText());
  }

  @Test
  public void parse_content_withFunctionCall() throws Exception {
    FunctionCall fc = FunctionCall.builder().name("myFunction").build();
    Content content = Content.fromParts(Part.builder().functionCall(fc).build());
    Parser.ParsedContent result =
        new Parser(null, 100, null, true).parse(content, "trace", "span").get();

    assertEquals(1, result.parts().size());
    JsonNode partData = result.parts().get(0);
    assertEquals("application/json", partData.get("mime_type").asText());
    assertEquals("Function: myFunction", partData.get("text").asText());
    assertTrue(partData.get("part_attributes").asText().contains("myFunction"));
  }

  @Test
  public void parse_list_truncatesElements() throws Exception {
    List<String> list =
        Arrays.asList("short", "this is a very long string that should be truncated");
    Parser.ParsedContent result =
        new Parser(null, 24, null, true).parse(list, "trace", "span").get();

    assertTrue(result.isTruncated());
    JsonNode arrayNode = result.content();
    assertTrue(arrayNode.isArray());
    assertEquals(2, arrayNode.size());
    assertEquals("short", arrayNode.get(0).asText());
    assertEquals("this is a ...[truncated]", arrayNode.get(1).asText());
  }

  @Test
  public void parse_withOffloader_offloadsLargeText() throws Exception {
    GcsOffloader offloader = mock(GcsOffloader.class);
    when(offloader.uploadContent(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture("gs://mock-bucket/path"));

    Content content =
        Content.fromParts(Part.fromText("this text is longer than 10 characters".repeat(100)));
    Parser.ParsedContent result =
        new Parser(offloader, 10, "conn", true).parse(content, "trace", "span").get();

    assertEquals(1, result.parts().size());
    JsonNode partData = result.parts().get(0);
    assertEquals("GCS_REFERENCE", partData.get("storage_mode").asText());
    assertEquals("gs://mock-bucket/path", partData.get("uri").asText());
    assertTrue(partData.get("text").asText().contains("[OFFLOADED]"));
    assertEquals("conn", partData.get("object_ref").get("authorizer").asText());
  }

  @Test
  public void parse_withOffloader_offloadsBinaryData() throws Exception {
    GcsOffloader offloader = mock(GcsOffloader.class);
    when(offloader.uploadContent(any(byte[].class), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture("gs://mock-bucket/image.png"));

    Blob blob = Blob.builder().data("fake-image".getBytes(UTF_8)).mimeType("image/png").build();
    Content content = Content.fromParts(Part.builder().inlineData(blob).build());
    Parser.ParsedContent result =
        new Parser(offloader, 100, "conn", true).parse(content, "trace", "span").get();

    assertEquals(1, result.parts().size());
    JsonNode partData = result.parts().get(0);
    assertEquals("GCS_REFERENCE", partData.get("storage_mode").asText());
    assertEquals("gs://mock-bucket/image.png", partData.get("uri").asText());
    assertEquals("image/png", partData.get("mime_type").asText());
    assertEquals("[MEDIA OFFLOADED]", partData.get("text").asText());
  }

  @Test
  public void truncate_variousInputs() {
    assertNull(JsonFormatter.truncate(null, 10));
    assertEquals("", JsonFormatter.truncate("", 10));
    assertEquals("short", JsonFormatter.truncate("short", 10));
    assertEquals("exactlyten", JsonFormatter.truncate("exactlyten", 10));

    // Simple truncation
    String truncated = JsonFormatter.truncate("this is a long string for budget 24", 24);
    assertEquals("this is a ...[truncated]", truncated);

    // Multi-byte truncation (UTF-8)
    // "こんにちはこんにちは" is 30 bytes
    String nihongo = "こんにちはこんにちは";
    String truncatedNihongo = JsonFormatter.truncate(nihongo, 20); // Should keep 2 chars (6 bytes)
    assertEquals("こん...[truncated]", truncatedNihongo);
  }

  @Test
  public void truncate_budgetSmallerThanSuffix_returnsPartialSuffix() {
    String longString = "this is a long string that should be truncated";
    assertEquals("...[t", JsonFormatter.truncate(longString, 5));
    assertEquals("", JsonFormatter.truncate(longString, 0));
    assertEquals("...[truncated]", JsonFormatter.truncate(longString, 14));
  }

  @Test
  public void truncateAndAddSuffix_coversCodePointSizes() {
    String s = "aαこ😀extra";
    String suffix = "...";

    assertEquals("a...", JsonFormatter.truncateAndAddSuffix(s, 4, suffix));
    assertEquals("aα...", JsonFormatter.truncateAndAddSuffix(s, 6, suffix));
    assertEquals("aαこ...", JsonFormatter.truncateAndAddSuffix(s, 9, suffix));
    assertEquals("aαこ😀...", JsonFormatter.truncateAndAddSuffix(s, 13, suffix));
    assertEquals("aαこ...", JsonFormatter.truncateAndAddSuffix(s, 12, suffix));
  }

  @Test
  public void parse_multibyteString_truncatesBasedOnBytes() throws Exception {
    // "こんにちはこんにちは" is 30 bytes, but 10 characters.
    String nihongo = "こんにちはこんにちは";
    // With budget 20, effective budget is 6, so only 2 characters (6 bytes) should be kept.
    Parser.ParsedContent result =
        new Parser(null, 20, null, true).parse(nihongo, "trace", "span").get();

    assertTrue(result.isTruncated());
    assertEquals("こん...[truncated]", result.content().asText());
  }

  @Test
  public void parse_multibyteContent_truncatesBasedOnBytes() throws Exception {
    Content content = Content.fromParts(Part.fromText("こんにちはこんにちは"));
    Parser.ParsedContent result =
        new Parser(null, 20, null, true).parse(content, "trace", "span").get();

    assertTrue(result.isTruncated());
    assertEquals("こん...[truncated]", result.content().get("text_summary").asText());
  }

  @Test
  public void smartTruncate_withCycle_detectsCycle() {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode node = mapper.createObjectNode();
    node.set("child", node);

    // Verify that smartTruncate handles circular JsonNode structures by detecting the cycle.
    JsonFormatter.TruncationResult result = JsonFormatter.smartTruncate(node, 100);

    assertTrue(result.isTruncated());
    assertEquals("[cycle detected]", result.node().get("child").asText());
  }

  @Test
  public void smartTruncate_redactsSensitiveTopLevelKeys() {
    ImmutableMap<String, Object> map =
        ImmutableMap.of("api_key", "sk-secret", "password", "hunter2", "keep", "value");
    JsonFormatter.TruncationResult result = JsonFormatter.smartTruncate(map, 5000);

    JsonNode node = result.node();
    assertEquals("[REDACTED]", node.get("api_key").asText());
    assertEquals("[REDACTED]", node.get("password").asText());
    assertEquals("value", node.get("keep").asText());
    // Redaction must not flip the truncation flag.
    assertFalse(result.isTruncated());
  }

  @Test
  public void smartTruncate_redactsCaseInsensitiveAndTempPrefixKeys() {
    ImmutableMap<String, Object> map =
        ImmutableMap.of("Access_Token", "abc", "temp:scratch", "xyz", "keep", "ok");
    JsonNode node = JsonFormatter.smartTruncate(map, 5000).node();

    assertEquals("[REDACTED]", node.get("Access_Token").asText());
    assertEquals("[REDACTED]", node.get("temp:scratch").asText());
    assertEquals("ok", node.get("keep").asText());
  }

  @Test
  public void smartTruncate_redactsNestedSensitiveKeys() {
    ImmutableMap<String, Object> map =
        ImmutableMap.of("outer", ImmutableMap.of("client_secret", "s", "ok", "v"));
    JsonNode node = JsonFormatter.smartTruncate(map, 5000).node();

    assertEquals("[REDACTED]", node.get("outer").get("client_secret").asText());
    assertEquals("v", node.get("outer").get("ok").asText());
  }

  @Test
  public void smartTruncate_depthGuard_replacesDeepSubtreeWithSentinel() {
    java.util.Map<String, Object> root = new java.util.HashMap<>();
    java.util.Map<String, Object> cur = root;
    for (int i = 0; i < 300; i++) {
      java.util.Map<String, Object> next = new java.util.HashMap<>();
      cur.put("child", next);
      cur = next;
    }

    JsonFormatter.TruncationResult result = JsonFormatter.smartTruncate(root, 5000);
    assertTrue(result.isTruncated());

    JsonNode node = result.node();
    boolean foundSentinel = false;
    for (int i = 0; i < 400; i++) {
      JsonNode child = node.get("child");
      if (child == null) {
        break;
      }
      if (child.isTextual() && JsonFormatter.MAX_DEPTH_MESSAGE.equals(child.asText())) {
        foundSentinel = true;
        break;
      }
      node = child;
    }
    assertTrue("Expected the max-depth sentinel in the deep chain", foundSentinel);
  }
}
