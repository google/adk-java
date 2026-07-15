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

import static com.google.adk.plugins.agentanalytics.JsonFormatter.mapper;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.smartTruncate;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.truncate;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.truncateAndAddSuffix;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.truncateWithStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.agentanalytics.JsonFormatter.TruncationResult;
import com.google.auto.value.AutoValue;
import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.threeten.bp.Instant;
import org.threeten.bp.LocalDate;
import org.threeten.bp.ZoneOffset;

/** Utility for parsing content for BigQuery logging. */
final class Parser {
  private static final String DEFAULT_EXTENSION = ".bin";
  private static final int MAX_OFFLOADED_TEXT_LENGTH = 200;
  private static final Logger logger = Logger.getLogger(Parser.class.getName());
  private static final int INLINE_TEXT_LIMIT = 32 * 1024; // 32KB limit
  private static final String UPLOAD_FAILED_MESSAGE = "[UPLOAD FAILED]";
  private static final String MEDIA_OFFLOADED_MESSAGE = "[MEDIA OFFLOADED]";
  private static final String BINARY_DATA_MESSAGE = "[BINARY DATA]";
  private static final String TEXT_OFFLOADED_SUFFIX = "... [OFFLOADED]";

  private final @Nullable GcsOffloader offloader;
  private final int maxLength;
  private final @Nullable String connectionId;
  private final boolean logMultiModalContent;

  Parser(
      @Nullable GcsOffloader offloader,
      int maxLength,
      @Nullable String connectionId,
      boolean logMultiModalContent) {
    this.offloader = offloader;
    this.maxLength = maxLength;
    this.connectionId = connectionId;
    this.logMultiModalContent = logMultiModalContent;
  }

  @AutoValue
  abstract static class ParsedContent {
    abstract ImmutableList<JsonNode> parts();

    abstract JsonNode content();

    abstract boolean isTruncated();

    static ParsedContent create(
        ImmutableList<JsonNode> parts, JsonNode content, boolean isTruncated) {
      return new AutoValue_Parser_ParsedContent(parts, content, isTruncated);
    }
  }

  @AutoValue
  abstract static class ParsedContentObject {
    abstract ArrayNode parts();

    abstract String summary();

    abstract boolean isTruncated();

    static ParsedContentObject create(ArrayNode parts, String summary, boolean isTruncated) {
      return new AutoValue_Parser_ParsedContentObject(parts, summary, isTruncated);
    }
  }

  @AutoValue
  abstract static class ContentPart {
    @JsonProperty("part_index")
    abstract int partIndex();

    @JsonProperty("mime_type")
    abstract @Nullable String mimeType();

    @JsonProperty("uri")
    abstract @Nullable String uri();

    @JsonProperty("text")
    abstract @Nullable String text();

    @JsonProperty("part_attributes")
    abstract String partAttributes();

    @JsonProperty("storage_mode")
    abstract String storageMode();

    @JsonProperty("object_ref")
    abstract @Nullable JsonNode objectRef();

    static Builder builder() {
      return new AutoValue_Parser_ContentPart.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setPartIndex(int value);

      abstract Builder setMimeType(@Nullable String value);

      abstract Builder setUri(@Nullable String value);

      abstract Builder setText(@Nullable String value);

      abstract Builder setPartAttributes(String value);

      abstract Builder setStorageMode(String value);

      abstract Builder setObjectRef(@Nullable JsonNode value);

      abstract ContentPart build();
    }
  }

  @AutoValue
  abstract static class ObjectRef {
    @JsonProperty("uri")
    abstract @Nullable String uri();

    @JsonProperty("version")
    abstract @Nullable String version();

    @JsonProperty("authorizer")
    abstract @Nullable String authorizer();

    @JsonProperty("details")
    abstract @Nullable JsonNode details();

    static ObjectRef create(
        @Nullable String uri,
        @Nullable String version,
        @Nullable String authorizer,
        @Nullable JsonNode details) {
      return new AutoValue_Parser_ObjectRef(uri, version, authorizer, details);
    }
  }

  /**
   * Parses content into JSON payload and content parts, matching Python implementation.
   *
   * @param content the content to parse
   * @param traceId the trace ID for GCS path
   * @param spanId the span ID for GCS path
   * @return a CompletableFuture of ParsedContent object
   */
  CompletableFuture<ParsedContent> parse(Object content, String traceId, String spanId) {
    if (content instanceof LlmRequest llmRequest) {
      ObjectNode jsonPayload = mapper.createObjectNode();
      ArrayNode messages = mapper.createArrayNode();
      List<CompletableFuture<ParsedContentObject>> futures = new ArrayList<>();
      List<Content> contents = llmRequest.contents();

      for (Content c : contents) {
        futures.add(parseContentObject(c, traceId, spanId));
      }

      CompletableFuture<ParsedContentObject> systemFuture = null;
      if (llmRequest.config().isPresent()
          && llmRequest.config().get().systemInstruction().isPresent()) {
        systemFuture =
            parseContentObject(
                llmRequest.config().get().systemInstruction().get(), traceId, spanId);
        futures.add(systemFuture);
      }
      CompletableFuture<ParsedContentObject> finalSystemFuture = systemFuture;
      return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
          .thenApply(
              v -> {
                boolean isTruncated = false;
                ArrayNode contentParts = mapper.createArrayNode();
                for (int i = 0; i < contents.size(); i++) {
                  ParsedContentObject res = futures.get(i).join();
                  isTruncated = isTruncated || res.isTruncated();
                  contentParts.addAll(res.parts());

                  ObjectNode message = mapper.createObjectNode();
                  message.put("role", contents.get(i).role().orElse("unknown"));
                  message.put("content", res.summary());
                  messages.add(message);
                }
                if (!messages.isEmpty()) {
                  jsonPayload.set("prompt", messages);
                }
                if (finalSystemFuture != null) {
                  ParsedContentObject res = finalSystemFuture.join();
                  isTruncated = isTruncated || res.isTruncated();
                  contentParts.addAll(res.parts());
                  jsonPayload.put("system_prompt", res.summary());
                }
                return ParsedContent.create(
                    ImmutableList.copyOf(contentParts), jsonPayload, isTruncated);
              });
    }
    if (content instanceof LlmResponse llmResponse) {
      ObjectNode jsonPayload = mapper.createObjectNode();
      return parseContentObject(llmResponse.content().orElse(null), traceId, spanId)
          .thenApply(
              parsed -> {
                ObjectNode summaryNode = mapper.createObjectNode();
                summaryNode.put("text_summary", parsed.summary());
                jsonPayload.set("response", summaryNode);
                llmResponse
                    .usageMetadata()
                    .ifPresent(
                        usage -> {
                          ObjectNode usageNode = jsonPayload.putObject("usage");
                          usage.promptTokenCount().ifPresent(c -> usageNode.put("prompt", c));
                          usage
                              .candidatesTokenCount()
                              .ifPresent(c -> usageNode.put("completion", c));
                          usage.totalTokenCount().ifPresent(c -> usageNode.put("total", c));
                        });

                return ParsedContent.create(
                    ImmutableList.copyOf(parsed.parts()), jsonPayload, parsed.isTruncated());
              });
    }
    if (content instanceof Content || content instanceof Part) {
      return parseContentObject(content, traceId, spanId)
          .thenApply(
              parsed -> {
                ObjectNode summaryNode = mapper.createObjectNode();
                summaryNode.put("text_summary", parsed.summary());
                return ParsedContent.create(
                    ImmutableList.copyOf(parsed.parts()), summaryNode, parsed.isTruncated());
              });
    }
    // Fallback for types that don't support multi-part content
    TruncationResult result;
    if (content instanceof String s) {
      result = truncateWithStatus(s, maxLength);
    } else {
      result = smartTruncate(content, maxLength);
    }
    return CompletableFuture.completedFuture(
        ParsedContent.create(ImmutableList.of(), result.node(), result.isTruncated()));
  }

  /**
   * Parses a Content or Part object into summary text and content parts.
   *
   * @param content the Content or Part object to parse
   * @param traceId the trace ID for GCS path
   * @param spanId the span ID for GCS path
   * @return a CompletableFuture of ParsedContentObject containing parts, summary, and truncation
   *     flag
   */
  private CompletableFuture<ParsedContentObject> parseContentObject(
      Object content, String traceId, String spanId) {
    List<Part> parts;
    if (content instanceof Content c) {
      parts = c.parts().orElse(ImmutableList.of());
    } else if (content instanceof Part p) {
      parts = ImmutableList.of(p);
    } else {
      return CompletableFuture.completedFuture(
          ParsedContentObject.create(mapper.createArrayNode(), "", false));
    }

    List<CompletableFuture<TruncationResult>> partFutures = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) {
      partFutures.add(processPart(parts.get(i), i, traceId, spanId));
    }

    return CompletableFuture.allOf(partFutures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            v -> {
              ArrayNode contentParts = mapper.createArrayNode();
              List<String> summaries = new ArrayList<>();
              boolean isTruncated = false;

              for (CompletableFuture<TruncationResult> future : partFutures) {
                TruncationResult res = future.join();
                contentParts.add(res.node());
                isTruncated = isTruncated || res.isTruncated();
                JsonNode textNode = res.node().get("text");
                if (textNode != null && !textNode.isNull()) {
                  summaries.add(textNode.asText());
                }
              }

              String summary = String.join(" | ", summaries);
              if (Utf8.encodedLength(summary) > maxLength) {
                summary = truncate(summary, maxLength);
                isTruncated = true;
              }

              return ParsedContentObject.create(contentParts, summary, isTruncated);
            });
  }

  private CompletableFuture<TruncationResult> processPart(
      Part part, int index, String traceId, String spanId) {
    ContentPart.Builder partBuilder =
        ContentPart.builder()
            .setPartIndex(index)
            .setMimeType("text/plain")
            .setUri(null)
            .setText(null)
            .setPartAttributes("{}")
            .setStorageMode("INLINE")
            .setObjectRef(null);

    // CASE A: It is already a URI (e.g. from user input)
    if (part.fileData().isPresent()) {
      FileData fileData = part.fileData().get();
      partBuilder
          .setStorageMode("EXTERNAL_URI")
          .setUri(fileData.fileUri().orElse(null))
          .setMimeType(fileData.mimeType().orElse(null));
      return CompletableFuture.completedFuture(
          TruncationResult.create(mapper.valueToTree(partBuilder.build()), false));
    }
    // CASE B: It is Binary/Inline Data (Image/Blob)
    if (part.inlineData().isPresent()) {
      Blob blob = part.inlineData().get();
      String mimeType = blob.mimeType().orElse("application/octet-stream");
      if (logMultiModalContent && offloader != null) {
        String ext = MimeTypeMapper.getExtension(mimeType);
        if (ext.isEmpty()) {
          ext = DEFAULT_EXTENSION;
        }

        String path =
            String.format(
                "%s/%s/%s_p%d_%s%s",
                getLocalDate(), traceId, spanId, index, UUID.randomUUID(), ext);
        return offloader
            .uploadContent(blob.data().orElse(new byte[0]), mimeType, path)
            .handle(
                (uri, ex) -> {
                  if (ex != null) {
                    logger.log(Level.WARNING, "Failed to offload content to GCS", ex);
                    partBuilder.setText(UPLOAD_FAILED_MESSAGE);
                  } else {
                    ObjectNode details = mapper.createObjectNode();
                    ObjectNode gcsMetadata = details.putObject("gcs_metadata");
                    gcsMetadata.put("content_type", mimeType);

                    partBuilder
                        .setStorageMode("GCS_REFERENCE")
                        .setUri(uri)
                        .setMimeType(mimeType)
                        .setText(MEDIA_OFFLOADED_MESSAGE)
                        .setObjectRef(
                            mapper.valueToTree(ObjectRef.create(uri, null, connectionId, details)));
                  }
                  return TruncationResult.create(mapper.valueToTree(partBuilder.build()), false);
                });
      } else {
        partBuilder.setText(BINARY_DATA_MESSAGE).setMimeType(mimeType);
        return CompletableFuture.completedFuture(
            TruncationResult.create(mapper.valueToTree(partBuilder.build()), false));
      }
    }
    // CASE C: Text
    if (part.text().isPresent()) {
      String text = part.text().get();
      int textLen = Utf8.encodedLength(text);
      int offloadThreshold = Math.min(INLINE_TEXT_LIMIT, maxLength);

      if (offloader != null && textLen > offloadThreshold) {

        String path =
            String.format(
                "%s/%s/%s_p%d_%s.txt", getLocalDate(), traceId, spanId, index, UUID.randomUUID());
        return offloader
            .uploadContent(text, "text/plain", path)
            .handle(
                (uri, ex) -> {
                  if (ex != null) {
                    logger.log(Level.WARNING, "Failed to offload text to GCS", ex);
                    TruncationResult res = truncateWithStatus(text, maxLength);
                    partBuilder.setText(res.node().asText());
                    return TruncationResult.create(
                        mapper.valueToTree(partBuilder.build()), res.isTruncated());
                  } else {
                    ObjectNode details = mapper.createObjectNode();
                    ObjectNode gcsMetadata = details.putObject("gcs_metadata");
                    gcsMetadata.put("content_type", "text/plain");

                    partBuilder
                        .setStorageMode("GCS_REFERENCE")
                        .setUri(uri)
                        .setMimeType("text/plain")
                        .setText(
                            truncateAndAddSuffix(
                                text, MAX_OFFLOADED_TEXT_LENGTH, TEXT_OFFLOADED_SUFFIX))
                        .setObjectRef(
                            mapper.valueToTree(ObjectRef.create(uri, null, connectionId, details)));
                    return TruncationResult.create(mapper.valueToTree(partBuilder.build()), true);
                  }
                });
      } else {
        TruncationResult res = truncateWithStatus(text, maxLength);
        partBuilder.setText(res.node().asText());
        return CompletableFuture.completedFuture(
            TruncationResult.create(mapper.valueToTree(partBuilder.build()), res.isTruncated()));
      }
    }
    if (part.functionCall().isPresent()) {
      FunctionCall fc = part.functionCall().get();
      ObjectNode partAttributes = mapper.createObjectNode();
      partAttributes.put("function_name", fc.name().orElse("unknown"));
      partBuilder
          .setMimeType("application/json")
          .setText("Function: " + fc.name().orElse("unknown"))
          .setPartAttributes(partAttributes.toString());
      return CompletableFuture.completedFuture(
          TruncationResult.create(mapper.valueToTree(partBuilder.build()), false));
    }
    return CompletableFuture.completedFuture(
        TruncationResult.create(mapper.valueToTree(partBuilder.build()), false));
  }

  /** Formats Content parts into an ArrayNode for BigQuery logging. */
  ArrayNode formatContentParts(Optional<Content> content) {
    ArrayNode partsArray = mapper.createArrayNode();
    if (content.isEmpty()) {
      return partsArray;
    }

    List<Part> parts = content.get().parts().orElse(ImmutableList.of());

    for (int i = 0; i < parts.size(); i++) {
      Part part = parts.get(i);
      ObjectNode partObj = mapper.createObjectNode();
      partObj.put("part_index", i);
      partObj.put("storage_mode", "INLINE");

      if (part.text().isPresent()) {
        partObj.put("mime_type", "text/plain");
        partObj.put("text", truncate(part.text().get(), maxLength));
      } else if (part.inlineData().isPresent()) {
        Blob blob = part.inlineData().get();
        partObj.put("mime_type", blob.mimeType().orElse(""));
        partObj.put("text", BINARY_DATA_MESSAGE);
      } else if (part.fileData().isPresent()) {
        FileData fileData = part.fileData().get();
        partObj.put("mime_type", fileData.mimeType().orElse(""));
        partObj.put("uri", fileData.fileUri().orElse(""));
        partObj.put("storage_mode", "EXTERNAL_URI");
      }
      partsArray.add(partObj);
    }
    return partsArray;
  }

  private LocalDate getLocalDate() {
    return Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
  }
}
