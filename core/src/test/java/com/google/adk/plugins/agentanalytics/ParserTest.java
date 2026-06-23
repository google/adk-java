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
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ParserTest {
  private Parser parser;

  @Before
  public void setUp() {
    parser = new Parser(null, 100, "connectionId", true);
  }

  @Test
  public void parse_part_coversLine280() throws Exception {
    Part part = Part.fromText("test part");
    CompletableFuture<Parser.ParsedContent> future = parser.parse(part, "traceId", "spanId");
    Parser.ParsedContent result = future.get();

    assertEquals("{\"text_summary\":\"test part\"}", result.content().toString());
    assertEquals(1, result.parts().size());
    assertEquals("test part", result.parts().get(0).get("text").asText());
  }

  @Test
  public void parse_part_withInlineData_coversProcessPart() throws Exception {
    Blob blob = Blob.builder().mimeType("image/png").data(new byte[] {1, 2, 3}).build();
    Part part = Part.builder().inlineData(blob).build();
    CompletableFuture<Parser.ParsedContent> future = parser.parse(part, "traceId", "spanId");
    Parser.ParsedContent result = future.get();

    assertEquals(1, result.parts().size());
    ObjectNode node = (ObjectNode) result.parts().get(0);
    assertEquals("image/png", node.get("mime_type").asText());
    assertEquals("[BINARY DATA]", node.get("text").asText());
    assertEquals("INLINE", node.get("storage_mode").asText());
  }

  @Test
  public void formatContentParts_inlineData_coversLine446() {
    Blob blob = Blob.builder().mimeType("image/png").data(new byte[] {1, 2, 3}).build();
    Part part = Part.builder().inlineData(blob).build();
    Content content = Content.fromParts(part);

    ArrayNode nodes = parser.formatContentParts(Optional.of(content));

    assertEquals(1, nodes.size());
    ObjectNode node = (ObjectNode) nodes.get(0);
    assertEquals("image/png", node.get("mime_type").asText());
    assertEquals("[BINARY DATA]", node.get("text").asText());
  }

  @Test
  public void formatContentParts_fileData_coversLine450() {
    FileData fileData =
        FileData.builder().mimeType("application/pdf").fileUri("gs://bucket/file.pdf").build();
    Part part = Part.builder().fileData(fileData).build();
    Content content = Content.fromParts(part);

    ArrayNode nodes = parser.formatContentParts(Optional.of(content));

    assertEquals(1, nodes.size());
    ObjectNode node = (ObjectNode) nodes.get(0);
    assertEquals("application/pdf", node.get("mime_type").asText());
    assertEquals("gs://bucket/file.pdf", node.get("uri").asText());
    assertEquals("EXTERNAL_URI", node.get("storage_mode").asText());
  }

  @Test
  public void parse_multipartContent_coversLine310() throws Exception {
    // maxLength is 100.
    String longText = "a".repeat(100);
    Content content = Content.fromParts(Part.fromText("Part 1"), Part.fromText(longText));

    // Call private method using helper if necessary, but parseContentObject is private.
    // However, parse(Object content, ...) calls it.
    CompletableFuture<Parser.ParsedContent> future = parser.parse(content, "traceId", "spanId");
    Parser.ParsedContent result = future.get();

    assertTrue(result.isTruncated());
  }
}
