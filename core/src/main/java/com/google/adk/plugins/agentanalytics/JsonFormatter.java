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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Optional;

/** Utility for formatting and truncating content for BigQuery logging. */
final class JsonFormatter {
  private static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private JsonFormatter() {}

  /** Formats Content parts into an ArrayNode for BigQuery logging. */
  public static ArrayNode formatContentParts(Optional<Content> content, int maxLength) {
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
        partObj.put("text", truncateString(part.text().get(), maxLength));
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

  /** Recursively truncates long strings inside an object and returns a Jackson JsonNode. */
  public static JsonNode smartTruncate(Object obj, int maxLength) {
    if (obj == null) {
      return mapper.nullNode();
    }
    try {
      return recursiveSmartTruncate(mapper.valueToTree(obj), maxLength);
    } catch (IllegalArgumentException e) {
      // Fallback for types that mapper can't handle directly as a tree
      return mapper.valueToTree(String.valueOf(obj));
    }
  }

  private static JsonNode recursiveSmartTruncate(JsonNode node, int maxLength) {
    if (node.isTextual()) {
      return mapper.valueToTree(truncateString(node.asText(), maxLength));
    } else if (node.isObject()) {
      ObjectNode newNode = mapper.createObjectNode();
      node.properties()
          .iterator()
          .forEachRemaining(
              entry -> {
                newNode.set(entry.getKey(), recursiveSmartTruncate(entry.getValue(), maxLength));
              });
      return newNode;
    } else if (node.isArray()) {
      ArrayNode newNode = mapper.createArrayNode();
      for (JsonNode element : node) {
        newNode.add(recursiveSmartTruncate(element, maxLength));
      }
      return newNode;
    }
    return node;
  }

  private static String truncateString(String s, int maxLength) {
    if (s == null || s.length() <= maxLength) {
      return s;
    }
    return s.substring(0, maxLength) + "...[truncated]";
  }
}
