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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
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

  @Test
  public void parse_inlineDataUploadFailure_preservesMimeTypeAndFallback() throws Exception {
    GcsOffloader failingOffloader = mock(GcsOffloader.class);
    when(failingOffloader.uploadContent(any(byte[].class), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("GCS boom")));
    Parser offloadingParser = new Parser(failingOffloader, 100, "connectionId", true);

    JsonNode png = parseOnlyPart(offloadingParser, inlinePart("image/png"));
    JsonNode pdf = parseOnlyPart(offloadingParser, inlinePart("application/pdf"));
    JsonNode empty = parseOnlyPart(offloadingParser, inlinePart(""));
    JsonNode absent = parseOnlyPart(offloadingParser, inlinePartWithoutMimeType());

    verify(failingOffloader).uploadContent(any(byte[].class), eq("image/png"), anyString());
    verify(failingOffloader).uploadContent(any(byte[].class), eq("application/pdf"), anyString());
    verify(failingOffloader, times(2))
        .uploadContent(any(byte[].class), eq("application/octet-stream"), anyString());
    assertInlinePart(png, "image/png", "[UPLOAD FAILED]");
    assertInlinePart(pdf, "application/pdf", "[UPLOAD FAILED]");
    assertInlinePart(empty, "application/octet-stream", "[UPLOAD FAILED]");
    assertInlinePart(absent, "application/octet-stream", "[UPLOAD FAILED]");
  }

  @Test
  public void parse_inlineDataUploadSuccess_preservesMimeTypeAndSetsGcsReference()
      throws Exception {
    GcsOffloader succeedingOffloader = mock(GcsOffloader.class);
    when(succeedingOffloader.uploadContent(any(byte[].class), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture("gs://test-bucket/blob.bin"));
    Parser offloadingParser = new Parser(succeedingOffloader, 100, "connectionId", true);

    JsonNode png = parseOnlyPart(offloadingParser, inlinePart("image/png"));
    JsonNode empty = parseOnlyPart(offloadingParser, inlinePart(""));
    JsonNode absent = parseOnlyPart(offloadingParser, inlinePartWithoutMimeType());

    verify(succeedingOffloader).uploadContent(any(byte[].class), eq("image/png"), anyString());
    verify(succeedingOffloader, times(2))
        .uploadContent(any(byte[].class), eq("application/octet-stream"), anyString());
    assertOffloadedBinaryPart(png, "image/png", "gs://test-bucket/blob.bin");
    assertOffloadedBinaryPart(empty, "application/octet-stream", "gs://test-bucket/blob.bin");
    assertOffloadedBinaryPart(absent, "application/octet-stream", "gs://test-bucket/blob.bin");
  }

  @Test
  public void parse_inlineDataWithoutOffload_preservesMimeTypeAndEmptyFallback() throws Exception {
    GcsOffloader unusedOffloader = mock(GcsOffloader.class);
    Parser noOffloaderParser = new Parser(null, 100, "connectionId", true);
    Parser multiModalOffParser = new Parser(unusedOffloader, 100, "connectionId", false);

    JsonNode wavNoOffloader = parseOnlyPart(noOffloaderParser, inlinePart("audio/wav"));
    JsonNode emptyNoOffloader = parseOnlyPart(noOffloaderParser, inlinePart(""));
    JsonNode absentNoOffloader = parseOnlyPart(noOffloaderParser, inlinePartWithoutMimeType());
    JsonNode wavMultiModalOff = parseOnlyPart(multiModalOffParser, inlinePart("audio/wav"));
    JsonNode emptyMultiModalOff = parseOnlyPart(multiModalOffParser, inlinePart(""));
    JsonNode absentMultiModalOff = parseOnlyPart(multiModalOffParser, inlinePartWithoutMimeType());

    verifyNoInteractions(unusedOffloader);
    assertInlinePart(wavNoOffloader, "audio/wav", "[BINARY DATA]");
    assertInlinePart(emptyNoOffloader, "application/octet-stream", "[BINARY DATA]");
    assertInlinePart(absentNoOffloader, "application/octet-stream", "[BINARY DATA]");
    assertInlinePart(wavMultiModalOff, "audio/wav", "[BINARY DATA]");
    assertInlinePart(emptyMultiModalOff, "application/octet-stream", "[BINARY DATA]");
    assertInlinePart(absentMultiModalOff, "application/octet-stream", "[BINARY DATA]");
  }

  @Test
  public void parse_largeTextWithLogMultiModalFalse_skipsOffloadAndKeepsFullText()
      throws Exception {
    String largeText = "a".repeat(40 * 1024);
    GcsOffloader unusedOffloader = succeedingTextOffloader();

    Parser.ParsedContent kept =
        new Parser(unusedOffloader, 100 * 1024, "connectionId", false)
            .parse(Content.fromParts(Part.fromText(largeText)), "traceId", "spanId")
            .get();

    verifyNoInteractions(unusedOffloader);
    assertFalse(kept.isTruncated());
    String keptSummary = kept.content().get("text_summary").asText();
    assertEquals(largeText.length(), keptSummary.length());
    assertTrue("text_summary must keep the full text", keptSummary.equals(largeText));
    assertEquals("INLINE", kept.parts().get(0).get("storage_mode").asText());
  }

  @Test
  public void parse_largeTextWithLogMultiModalTrue_offloadsToGcsAndTruncatesInlinePreview()
      throws Exception {
    String largeText = "a".repeat(40 * 1024);
    GcsOffloader usedOffloader = succeedingTextOffloader();

    Parser.ParsedContent offloaded =
        new Parser(usedOffloader, 100 * 1024, "connectionId", true)
            .parse(Content.fromParts(Part.fromText(largeText)), "traceId", "spanId")
            .get();

    verify(usedOffloader).uploadContent(eq(largeText), eq("text/plain"), anyString());
    assertTrue(offloaded.isTruncated());
    assertEquals("GCS_REFERENCE", offloaded.parts().get(0).get("storage_mode").asText());
    assertTrue(offloaded.parts().get(0).get("text").asText().endsWith("... [OFFLOADED]"));
  }

  private static Part inlinePart(String mimeType) {
    return Part.builder()
        .inlineData(Blob.builder().mimeType(mimeType).data(new byte[] {1, 2, 3}).build())
        .build();
  }

  private static Part inlinePartWithoutMimeType() {
    return Part.builder().inlineData(Blob.builder().data(new byte[] {1, 2, 3}).build()).build();
  }

  private static JsonNode parseOnlyPart(Parser parser, Part part) throws Exception {
    Parser.ParsedContent result = parser.parse(part, "traceId", "spanId").get();
    assertEquals(1, result.parts().size());
    return result.parts().get(0);
  }

  private static void assertInlinePart(JsonNode part, String mimeType, String text) {
    assertEquals(mimeType, part.get("mime_type").asText());
    assertEquals(text, part.get("text").asText());
    assertEquals("INLINE", part.get("storage_mode").asText());
    assertTrue(part.get("uri").isNull());
    assertTrue(part.get("object_ref").isNull());
  }

  private static void assertOffloadedBinaryPart(JsonNode part, String mimeType, String uri) {
    assertEquals(mimeType, part.get("mime_type").asText());
    assertEquals("[MEDIA OFFLOADED]", part.get("text").asText());
    assertEquals("GCS_REFERENCE", part.get("storage_mode").asText());
    assertEquals(uri, part.get("uri").asText());
    JsonNode objectRef = part.get("object_ref");
    assertEquals(uri, objectRef.get("uri").asText());
    assertEquals("connectionId", objectRef.get("authorizer").asText());
    assertEquals(
        mimeType, objectRef.get("details").get("gcs_metadata").get("content_type").asText());
  }

  private static GcsOffloader succeedingTextOffloader() {
    GcsOffloader offloader = mock(GcsOffloader.class);
    when(offloader.uploadContent(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture("gs://test-bucket/large.txt"));
    return offloader;
  }
}
