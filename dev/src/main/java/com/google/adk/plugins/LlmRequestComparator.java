/*
 * Copyright 2025 Google LLC
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
package com.google.adk.plugins;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.flipkart.zjsonpatch.JsonDiff;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Compares LlmRequest objects for equality, excluding fields that can vary between runs.
 *
 * <p>Excluded fields:
 *
 * <ul>
 *   <li>liveConnectConfig - varies per run
 *   <li>config.httpOptions - varies per run
 *   <li>config.labels - varies per run
 * </ul>
 */
class LlmRequestComparator {
  private static final Pattern TEXT_PATH_PATTERN =
      Pattern.compile("/contents/\\d+/parts/\\d+/text");
  private static final Pattern PARAMS_RESULT_PATTERN =
      Pattern.compile("^(.*(?:parameters|result): )(\\{.*\\})(.*)$", Pattern.DOTALL);
  private final ObjectMapper objectMapper;

  LlmRequestComparator() {
    this.objectMapper = new ObjectMapper();
    // Register Jdk8Module to handle Optional types
    objectMapper.registerModule(new Jdk8Module());
    // Configure mix-ins to exclude runtime-variable fields
    objectMapper.addMixIn(GenerateContentConfig.class, GenerateContentConfigMixin.class);
    objectMapper.addMixIn(LlmRequest.class, LlmRequestMixin.class);
  }

  /**
   * Compares two LlmRequest objects for equality, excluding runtime-variable fields.
   *
   * @param recorded the recorded request
   * @param current the current request
   * @return true if the requests match (excluding runtime-variable fields)
   */
  boolean equals(LlmRequest recorded, LlmRequest current) {
    JsonNode recordedNode = toJsonNode(recorded);
    JsonNode currentNode = toJsonNode(current);
    JsonNode patch = JsonDiff.asJson(recordedNode, currentNode);
    patch = filterPatch(patch, recordedNode, currentNode);
    return patch.isEmpty();
  }

  /**
   * Generates a human-readable diff between two LlmRequest objects.
   *
   * @param recorded the recorded request
   * @param current the current request
   * @return a string describing the differences, or empty string if they match
   */
  String diff(LlmRequest recorded, LlmRequest current) {
    JsonNode recordedNode = toJsonNode(recorded);
    JsonNode currentNode = toJsonNode(current);
    JsonNode patch = JsonDiff.asJson(recordedNode, currentNode);
    patch = filterPatch(patch, recordedNode, currentNode);
    if (patch.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    for (JsonNode op : patch) {
      String operation = op.get("op").asText();
      String path = op.get("path").asText();

      if (operation.equals("replace")) {
        JsonNode oldValue = recordedNode.at(path);
        JsonNode newValue = op.get("value");
        sb.append(
            String.format(
                "Mismatch at %s:%n  recorded: %s%n  current:  %s%n%n", path, oldValue, newValue));
      } else if (operation.equals("add")) {
        JsonNode newValue = op.get("value");
        sb.append(String.format("Extra field at %s: %s%n%n", path, newValue));
      } else if (operation.equals("remove")) {
        JsonNode oldValue = recordedNode.at(path);
        sb.append(String.format("Missing field at %s: %s%n%n", path, oldValue));
      } else {
        // Fallback for other operations (move, copy, test)
        sb.append(op.toPrettyString())
            .append(System.lineSeparator())
            .append(System.lineSeparator());
      }
    }
    return sb.toString();
  }

  private JsonNode toJsonNode(LlmRequest request) {
    try {
      return objectMapper.readTree(objectMapper.writeValueAsString(request));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize request to JSON.", e);
    }
  }

  private JsonNode filterPatch(JsonNode patch, JsonNode recordedNode, JsonNode currentNode) {
    var filteredOps =
        StreamSupport.stream(patch.spliterator(), false)
            .filter(op -> !isEquivalentChange(op, recordedNode, currentNode))
            .collect(Collectors.toList());
    return objectMapper.valueToTree(filteredOps);
  }

  private boolean isEquivalentChange(JsonNode op, JsonNode recordedNode, JsonNode currentNode) {
    if (!op.get("op").asText().equals("replace")) {
      return false;
    }
    String path = op.get("path").asText();
    if (TEXT_PATH_PATTERN.matcher(path).matches()) {
      String recordedText = recordedNode.at(path).asText();
      String currentText = currentNode.at(path).asText();
      return areTextValuesEquivalent(recordedText, currentText);
    }
    return false;
  }

  private boolean areTextValuesEquivalent(String recorded, String current) {
    Matcher recordedMatcher = PARAMS_RESULT_PATTERN.matcher(recorded);
    Matcher currentMatcher = PARAMS_RESULT_PATTERN.matcher(current);

    if (recordedMatcher.matches() && currentMatcher.matches()) {
      if (!recordedMatcher.group(1).equals(currentMatcher.group(1))
          || !recordedMatcher.group(3).equals(currentMatcher.group(3))) {
        return false; // prefix or suffix differ
      }
      String recordedJson = recordedMatcher.group(2);
      String currentJson = currentMatcher.group(2);
      return compareJsonDictStrings(recordedJson, currentJson);
    }
    return recorded.equals(current);
  }

  private boolean compareJsonDictStrings(String recorded, String current) {
    String rStr = recorded.replace('\'', '"').replace("None", "null");
    String cStr = current.replace('\'', '"').replace("None", "null");
    try {
      JsonNode rNode = objectMapper.readTree(rStr);
      JsonNode cNode = objectMapper.readTree(cStr);

      if (rNode.equals(cNode)) {
        return true;
      }
    } catch (Exception e) {
      return false;
    }

    return false;
  }

  /** Mix-in to exclude GenerateContentConfig fields that vary between runs. */
  abstract static class GenerateContentConfigMixin {
    @JsonIgnore
    abstract Optional<HttpOptions> httpOptions();

    @JsonIgnore
    abstract Optional<Map<String, String>> labels();
  }

  /** Mix-in to exclude LlmRequest fields that vary between runs. */
  abstract static class LlmRequestMixin {
    @JsonIgnore
    abstract LiveConnectConfig liveConnectConfig();
  }
}
