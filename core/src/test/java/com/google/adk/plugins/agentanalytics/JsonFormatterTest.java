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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JsonFormatterTest {

  @Test
  public void parse_llmRequest_populatesPrompt() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.fromParts(Part.fromText("hello")).toBuilder().role("user").build()))
            .build();

    JsonFormatter.ParsedContent result = JsonFormatter.parse(request, 100);

    assertTrue(result.content().has("prompt"));
    ArrayNode prompt = (ArrayNode) result.content().get("prompt");
    assertEquals(1, prompt.size());
    assertEquals("user", prompt.get(0).get("role").asText());
    assertEquals("hello", prompt.get(0).get("content").asText());
    assertFalse(result.isTruncated());
  }

  @Test
  public void parse_llmRequest_populatesSystemPrompt() {
    LlmRequest request =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText("be helpful")))
                    .build())
            .build();

    JsonFormatter.ParsedContent result = JsonFormatter.parse(request, 100);

    assertTrue(result.content().has("system_prompt"));
    assertEquals("be helpful", result.content().get("system_prompt").asText());
    assertFalse(result.isTruncated());
  }

  @Test
  public void parse_string_truncates() {
    String longString = "this is a very long string that should be truncated";
    JsonFormatter.ParsedContent result = JsonFormatter.parse(longString, 10);

    assertTrue(result.isTruncated());
    assertEquals("this is a ...[truncated]", result.content().asText());
  }

  @Test
  public void parse_map_truncatesNested() {
    ImmutableMap<String, Object> map = ImmutableMap.of("key", "this is a long value");
    JsonFormatter.ParsedContent result = JsonFormatter.parse(map, 10);

    assertTrue(result.isTruncated());
    assertEquals("this is a ...[truncated]", result.content().get("key").asText());
  }

  @Test
  public void parse_content_returnsSummary() {
    Content content = Content.fromParts(Part.fromText("part 1"), Part.fromText("part 2"));
    JsonFormatter.ParsedContent result = JsonFormatter.parse(content, 100);

    assertEquals("part 1 | part 2", result.content().get("text_summary").asText());
    assertEquals(2, result.parts().size());
  }

  @Test
  public void parse_content_withFileData() {
    FileData fileData =
        FileData.builder().fileUri("gs://bucket/file.txt").mimeType("text/plain").build();
    Content content = Content.fromParts(Part.builder().fileData(fileData).build());
    JsonFormatter.ParsedContent result = JsonFormatter.parse(content, 100);

    assertEquals(1, result.parts().size());
    JsonNode partData = result.parts().get(0);
    assertEquals("EXTERNAL_URI", partData.get("storage_mode").asText());
    assertEquals("gs://bucket/file.txt", partData.get("uri").asText());
    assertEquals("text/plain", partData.get("mime_type").asText());
  }

  @Test
  public void parse_content_withFunctionCall() {
    FunctionCall fc = FunctionCall.builder().name("myFunction").build();
    Content content = Content.fromParts(Part.builder().functionCall(fc).build());
    JsonFormatter.ParsedContent result = JsonFormatter.parse(content, 100);

    assertEquals(1, result.parts().size());
    JsonNode partData = result.parts().get(0);
    assertEquals("application/json", partData.get("mime_type").asText());
    assertEquals("Function: myFunction", partData.get("text").asText());
    assertTrue(partData.get("part_attributes").asText().contains("myFunction"));
  }

  @Test
  public void parse_list_truncatesElements() {
    List<String> list =
        Arrays.asList("short", "this is a very long string that should be truncated");
    JsonFormatter.ParsedContent result = JsonFormatter.parse(list, 10);

    assertTrue(result.isTruncated());
    JsonNode arrayNode = result.content();
    assertTrue(arrayNode.isArray());
    assertEquals(2, arrayNode.size());
    assertEquals("short", arrayNode.get(0).asText());
    assertEquals("this is a ...[truncated]", arrayNode.get(1).asText());
  }
}
