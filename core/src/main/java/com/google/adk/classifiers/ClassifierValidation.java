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

package com.google.adk.classifiers;

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.JsonBaseModel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Checks the values of the classifier types and normalizes their JSON content, in place of Python
 * ADK's Pydantic validation. Error messages name only types and counts, never the values
 * themselves.
 */
final class ClassifierValidation {

  /** The deepest JSON content nesting accepted, as in Pydantic, which bounds recursion depth. */
  static final int MAX_JSON_CONTENT_DEPTH = 200;

  private static final ObjectReader JSON_READER =
      JsonBaseModel.getMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private ClassifierValidation() {}

  /**
   * Returns a copy of {@code json} with each number written as the long or double it parses to.
   *
   * @throws IllegalArgumentException if {@code json} nests more than {@link
   *     #MAX_JSON_CONTENT_DEPTH} deep or holds a non-finite number, an integer beyond a long, or a
   *     value that is not JSON
   */
  static JsonNode normalizedJson(JsonNode json) {
    return normalized(json, /* depth= */ 0);
  }

  /** Parses the text of a JSON object or array for {@link ClassifierContent#parse}. */
  static ClassifierContent parseContent(String json) {
    JsonNode node;
    try {
      node = JSON_READER.readTree(json);
    } catch (JsonProcessingException unused) {
      // The parser's message quotes the JSON, so the cause is dropped.
      throw new IllegalArgumentException("ClassifierContent must be valid JSON.");
    }
    if (node instanceof ObjectNode object) {
      return new ClassifierContent.JsonObject(object);
    }
    if (node instanceof ArrayNode array) {
      return new ClassifierContent.JsonArray(array);
    }
    throw new IllegalArgumentException("ClassifierContent must be a JSON object or array.");
  }

  /** Returns an unmodifiable copy of a choice's labels and their optional descriptions. */
  static Map<String, @Nullable ClassifierContent> choiceCriteria(
      Map<String, @Nullable ClassifierContent> criteria) {
    checkArgument(
        criteria.size() >= 2,
        "Choice requires at least 2 criteria labels, got %s.",
        criteria.size());
    checkArgument(!criteria.containsKey(null), "Choice criteria labels must not be null.");
    return Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
  }

  /** Checks that {@code value} is a probability; {@code what} names it in the error message. */
  static void checkProbability(String what, double value) {
    // The range check also rejects NaN, which compares false with everything.
    checkArgument(value >= 0.0 && value <= 1.0, "%s must be a finite number in [0, 1].", what);
  }

  /** Checks that every value of {@code probabilities} is a probability. */
  static void checkProbabilities(String what, Map<?, Double> probabilities) {
    probabilities.values().forEach(probability -> checkProbability(what, probability));
  }

  /** Checks that {@code confidence}, if present, is a probability. */
  static void checkConfidence(Optional<Double> confidence) {
    confidence.ifPresent(value -> checkProbability("confidence", value));
  }

  private static JsonNode normalized(JsonNode node, int depth) {
    if (node.isObject()) {
      checkDepth(depth);
      ObjectNode copy = JsonNodeFactory.instance.objectNode();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        copy.set(field.getKey(), normalized(field.getValue(), depth + 1));
      }
      return copy;
    }
    if (node.isArray()) {
      checkDepth(depth);
      ArrayNode copy = JsonNodeFactory.instance.arrayNode(node.size());
      for (JsonNode element : node) {
        copy.add(normalized(element, depth + 1));
      }
      return copy;
    }
    if (node.isTextual() || node.isBoolean() || node.isNull()) {
      return node;
    }
    // A scale-0 BigDecimal writes integer JSON text, such as 7, that parses back to a long.
    if (node.isIntegralNumber() || (node.isBigDecimal() && node.decimalValue().scale() == 0)) {
      checkArgument(node.canConvertToLong(), "JSON content integers must fit in a long.");
      return LongNode.valueOf(node.longValue());
    }
    if (node.isFloatingPointNumber()) {
      // Widening a float to a double adds digits, so its shortest decimal form is read instead.
      double number = node.isFloat() ? Double.parseDouble(node.asText()) : node.doubleValue();
      checkArgument(Double.isFinite(number), "JSON content numbers must be finite.");
      return DoubleNode.valueOf(number);
    }
    throw new IllegalArgumentException(
        "JSON content contains a value that is not JSON: " + node.getNodeType() + ".");
  }

  private static void checkDepth(int depth) {
    checkArgument(
        depth < MAX_JSON_CONTENT_DEPTH,
        "JSON content exceeds maximum nesting depth of %s.",
        MAX_JSON_CONTENT_DEPTH);
  }
}
