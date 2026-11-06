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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Checks the values of the classifier types and copies their JSON content, in place of Python ADK's
 * Pydantic validation. Error messages name only types and counts, never the values themselves.
 */
final class ClassifierValidation {

  /** The deepest JSON content nesting accepted, as in Pydantic, which bounds recursion depth. */
  static final int MAX_JSON_CONTENT_DEPTH = 200;

  private ClassifierValidation() {}

  /** Returns null for null, and otherwise what {@link #jsonContent} returns. */
  static @Nullable Object optionalJsonContent(@Nullable Object value) {
    return value == null ? null : jsonContent(value);
  }

  /**
   * Returns an unmodifiable deep copy of JSON content: a string, or a map or list of JSON values.
   *
   * @throws IllegalArgumentException if {@code value} is not JSON content
   */
  private static Object jsonContent(Object value) {
    if (value instanceof String) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      return jsonObject(map);
    }
    if (value instanceof List<?> list) {
      return jsonArray(list);
    }
    throw new IllegalArgumentException(
        "Expected string, map, or list JSON content, got " + typeName(value) + ".");
  }

  /** Returns an unmodifiable deep copy of a JSON object with string keys. */
  static Map<String, @Nullable Object> jsonObject(Map<?, ?> map) {
    return jsonObject(map, /* depth= */ 0);
  }

  private static Map<String, @Nullable Object> jsonObject(Map<?, ?> map, int depth) {
    checkDepth(depth);
    Map<String, @Nullable Object> copy = new LinkedHashMap<>();
    map.forEach(
        (key, value) -> {
          checkArgument(
              key instanceof String,
              "JSON content map keys must be strings, got %s.",
              typeName(key));
          copy.put((String) key, jsonValue(value, depth + 1));
        });
    return Collections.unmodifiableMap(copy);
  }

  /** Returns an unmodifiable deep copy of a JSON array. */
  static List<@Nullable Object> jsonArray(List<?> list) {
    return jsonArray(list, /* depth= */ 0);
  }

  private static List<@Nullable Object> jsonArray(List<?> list, int depth) {
    checkDepth(depth);
    List<@Nullable Object> copy = new ArrayList<>(list.size());
    for (Object item : list) {
      copy.add(jsonValue(item, depth + 1));
    }
    return Collections.unmodifiableList(copy);
  }

  /** Returns an unmodifiable deep copy of a choice's labels and their optional descriptions. */
  static Map<String, @Nullable Object> choiceCriteria(Map<?, ?> criteria) {
    checkArgument(
        criteria.size() >= 2,
        "Choice requires at least 2 criteria labels, got %s.",
        criteria.size());
    Map<String, @Nullable Object> copy = new LinkedHashMap<>();
    criteria.forEach(
        (label, description) -> {
          checkArgument(
              label instanceof String,
              "Choice criteria labels must be strings, got %s.",
              typeName(label));
          copy.put((String) label, optionalJsonContent(description));
        });
    return Collections.unmodifiableMap(copy);
  }

  /** Returns a deep copy of a score's rubric levels. */
  static ImmutableList<Object> scoreCriteria(List<Object> criteria) {
    checkArgument(
        criteria.size() >= 2,
        "Score requires at least 2 criteria levels, got %s.",
        criteria.size());
    ImmutableList.Builder<Object> copy = ImmutableList.builderWithExpectedSize(criteria.size());
    criteria.forEach(level -> copy.add(jsonContent(level)));
    return copy.build();
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

  /** Returns the simple name of the type of {@code value}, which an error message may show. */
  static String typeName(@Nullable Object value) {
    if (value == null) {
      return "null";
    }
    String name = value.getClass().getSimpleName();
    return name.isEmpty() ? value.getClass().getName() : name;
  }

  private static @Nullable Object jsonValue(@Nullable Object value, int depth) {
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) {
      return value;
    }
    if (value instanceof Double || value instanceof Float) {
      checkArgument(
          Double.isFinite(((Number) value).doubleValue()), "JSON content numbers must be finite.");
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      return jsonObject(map, depth);
    }
    if (value instanceof List<?> list) {
      return jsonArray(list, depth);
    }
    throw new IllegalArgumentException(
        "Unsupported value in JSON content: " + typeName(value) + ".");
  }

  private static void checkDepth(int depth) {
    checkArgument(
        depth < MAX_JSON_CONTENT_DEPTH,
        "JSON content exceeds maximum nesting depth of %s.",
        MAX_JSON_CONTENT_DEPTH);
  }
}
