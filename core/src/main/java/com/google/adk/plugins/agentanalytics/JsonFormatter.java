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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Utility for parsing, formatting and truncating content for BigQuery logging. */
final class JsonFormatter {
  private static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @AutoValue
  abstract static class TruncationResult {
    abstract JsonNode node();

    abstract boolean isTruncated();

    static TruncationResult create(JsonNode node, boolean isTruncated) {
      return new AutoValue_JsonFormatter_TruncationResult(node, isTruncated);
    }
  }

  @AutoValue
  abstract static class ParsedContent {
    abstract ImmutableList<JsonNode> parts();

    abstract JsonNode content();

    abstract boolean isTruncated();

    static ParsedContent create(
        ImmutableList<JsonNode> parts, JsonNode content, boolean isTruncated) {
      return new AutoValue_JsonFormatter_ParsedContent(parts, content, isTruncated);
    }
  }

  @AutoValue
  abstract static class ParsedContentObject {
    abstract ArrayNode parts();

    abstract String summary();

    abstract boolean isTruncated();

    static ParsedContentObject create(ArrayNode parts, String summary, boolean isTruncated) {
      return new AutoValue_JsonFormatter_ParsedContentObject(parts, summary, isTruncated);
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
    abstract @Nullable String objectRef();

    static Builder builder() {
      return new AutoValue_JsonFormatter_ContentPart.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setPartIndex(int value);

      abstract Builder setMimeType(@Nullable String value);

      abstract Builder setUri(@Nullable String value);

      abstract Builder setText(@Nullable String value);

      abstract Builder setPartAttributes(String value);

      abstract Builder setStorageMode(String value);

      abstract Builder setObjectRef(@Nullable String value);

      abstract ContentPart build();
    }
  }

  /**
   * Parses content into JSON payload and content parts, matching Python implementation.
   *
   * @param content the content to parse
   * @param maxLength the maximum length for text fields
   * @return a ParsedContent object
   */
  static ParsedContent parse(Object content, int maxLength) {
    JsonNode contentNode = mapper.nullNode();
    ArrayNode contentParts = mapper.createArrayNode();
    boolean isTruncated = false;

    if (content instanceof LlmRequest llmRequest) {
      ObjectNode jsonPayload = mapper.createObjectNode();
      // Handle prompt
      ArrayNode messages = mapper.createArrayNode();
      List<Content> contents = llmRequest.contents();
      for (Content c : contents) {
        String role = c.role().orElse("unknown");
        ParsedContentObject parsedContentObject = parseContentObject(c, maxLength);
        isTruncated = isTruncated || parsedContentObject.isTruncated();
        contentParts.addAll(parsedContentObject.parts());

        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", parsedContentObject.summary());
        messages.add(message);
      }
      if (!messages.isEmpty()) {
        jsonPayload.set("prompt", messages);
      }
      // Handle system instruction
      if (llmRequest.config().isPresent()
          && llmRequest.config().get().systemInstruction().isPresent()) {
        Content systemInstruction = llmRequest.config().get().systemInstruction().get();
        ParsedContentObject parsedSystemInstruction =
            parseContentObject(systemInstruction, maxLength);
        isTruncated = isTruncated || parsedSystemInstruction.isTruncated();
        contentParts.addAll(parsedSystemInstruction.parts());
        jsonPayload.put("system_prompt", parsedSystemInstruction.summary());
      }
      contentNode = jsonPayload;
    } else if (content instanceof Content || content instanceof Part) {
      ParsedContentObject parsedContentObject = parseContentObject(content, maxLength);
      ObjectNode summaryNode = mapper.createObjectNode();
      summaryNode.put("text_summary", parsedContentObject.summary());
      return ParsedContent.create(
          ImmutableList.copyOf(parsedContentObject.parts()),
          summaryNode,
          parsedContentObject.isTruncated());
    } else if (content instanceof String s) {
      TruncationResult result = truncateWithStatus(s, maxLength);
      contentNode = result.node();
      isTruncated = result.isTruncated();
    } else {
      TruncationResult result = smartTruncate(content, maxLength);
      contentNode = result.node();
      isTruncated = result.isTruncated();
    }
    return ParsedContent.create(ImmutableList.copyOf(contentParts), contentNode, isTruncated);
  }

  /**
   * Parses a Content or Part object into summary text and content parts.
   *
   * @param content the Content or Part object to parse
   * @param maxLength the maximum length of text fields before truncation
   * @return a ParsedContentObject containing parts, summary, and truncation flag
   */
  private static ParsedContentObject parseContentObject(Object content, int maxLength) {
    ArrayNode contentParts = mapper.createArrayNode();
    boolean isTruncated = false;
    List<String> summaryText = new ArrayList<>();

    List<Part> parts;
    if (content instanceof Content c) {
      parts = c.parts().orElse(ImmutableList.of());
    } else if (content instanceof Part p) {
      parts = ImmutableList.of(p);
    } else {
      return ParsedContentObject.create(contentParts, "", false);
    }

    for (int i = 0; i < parts.size(); i++) {
      Part part = parts.get(i);
      ContentPart.Builder partBuilder =
          ContentPart.builder()
              .setPartIndex(i)
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
      }
      // CASE B: It is Binary/Inline Data (Image/Blob)
      else if (part.inlineData().isPresent()) {
        // TODO: (b/485571635) Implement GCS offloading here.
        partBuilder
            .setText("[BINARY DATA]")
            .setMimeType(part.inlineData().get().mimeType().orElse(""));
      }
      // CASE C: Text
      else if (part.text().isPresent()) {
        String text = part.text().get();
        // TODO: (b/485571635) Implement GCS offloading if text length exceeds maxLength.
        if (text.length() > maxLength) {
          text = truncate(text, maxLength);
          isTruncated = true;
        }
        partBuilder.setText(text);
        summaryText.add(text);
      } else if (part.functionCall().isPresent()) {
        FunctionCall fc = part.functionCall().get();
        ObjectNode partAttributes = mapper.createObjectNode();
        partAttributes.put("function_name", fc.name().orElse("unknown"));
        partBuilder
            .setMimeType("application/json")
            .setText("Function: " + fc.name().orElse("unknown"))
            .setPartAttributes(partAttributes.toString());
      }
      contentParts.add(mapper.valueToTree(partBuilder.build()));
    }

    String summaryResult = String.join(" | ", summaryText);
    if (summaryResult.length() > maxLength) {
      summaryResult = truncate(summaryResult, maxLength);
      isTruncated = true;
    }

    return ParsedContentObject.create(contentParts, summaryResult, isTruncated);
  }

  /** Formats Content parts into an ArrayNode for BigQuery logging. */
  static ArrayNode formatContentParts(Optional<Content> content, int maxLength) {
    ArrayNode partsArray = mapper.createArrayNode();
    if (content.isEmpty() || content.get().parts() == null) {
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
        partObj.put("text", "[BINARY DATA]");
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

  /** Recursively truncates long strings inside an object and returns a TruncationResult. */
  static TruncationResult smartTruncate(Object obj, int maxLength) {
    if (obj == null) {
      return TruncationResult.create(mapper.nullNode(), false);
    }
    try {
      return recursiveSmartTruncate(mapper.valueToTree(obj), maxLength);
    } catch (IllegalArgumentException e) {
      // Fallback for types that mapper can't handle directly as a tree
      return truncateWithStatus(String.valueOf(obj), maxLength);
    }
  }

  static JsonNode convertToJsonNode(Object obj) {
    if (obj == null) {
      return mapper.nullNode();
    }
    try {
      return mapper.valueToTree(obj);
    } catch (IllegalArgumentException e) {
      // Fallback for types that mapper can't handle directly as a tree
      return mapper.valueToTree(String.valueOf(obj));
    }
  }

  private static TruncationResult recursiveSmartTruncate(JsonNode node, int maxLength) {
    boolean isTruncated = false;
    if (node.isTextual()) {
      String text = node.asText();
      if (text.length() > maxLength) {
        return TruncationResult.create(mapper.valueToTree(truncate(text, maxLength)), true);
      }
      return TruncationResult.create(node, false);
    } else if (node.isObject()) {
      ObjectNode newNode = mapper.createObjectNode();
      Set<Map.Entry<String, JsonNode>> properties = node.properties();
      for (Map.Entry<String, JsonNode> entry : properties) {
        TruncationResult res = recursiveSmartTruncate(entry.getValue(), maxLength);
        newNode.set(entry.getKey(), res.node());
        isTruncated = isTruncated || res.isTruncated();
      }
      return TruncationResult.create(newNode, isTruncated);
    } else if (node.isArray()) {
      ArrayNode newNode = mapper.createArrayNode();
      for (JsonNode element : node) {
        TruncationResult res = recursiveSmartTruncate(element, maxLength);
        newNode.add(res.node());
        isTruncated = isTruncated || res.isTruncated();
      }
      return TruncationResult.create(newNode, isTruncated);
    }
    return TruncationResult.create(node, false);
  }

  private static TruncationResult truncateWithStatus(String s, int maxLength) {
    if (s == null) {
      return TruncationResult.create(mapper.nullNode(), false);
    }
    if (s.length() <= maxLength) {
      return TruncationResult.create(mapper.valueToTree(s), false);
    }
    return TruncationResult.create(mapper.valueToTree(truncate(s, maxLength)), true);
  }

  private static String truncate(String s, int maxLength) {
    if (s == null || s.length() <= maxLength) {
      return s;
    }
    return s.substring(0, maxLength) + "...[truncated]";
  }

  /** Converts a JsonNode to a standard Java object (Map, List, etc.). */
  public static @Nullable Object toJavaObject(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return mapper.convertValue(node, Object.class);
  }

  private JsonFormatter() {}
}
