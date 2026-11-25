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

package com.google.adk.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads the value a message carries: its non-thought text, as it is or as JSON. */
final class MessageReader {

  private static final Logger logger = LoggerFactory.getLogger(MessageReader.class);

  /** Stands for text that is not JSON, since the JSON text null reads as a null value. */
  static final Object NOT_JSON = new Object();

  private static final ObjectReader STRICT_JSON =
      JsonBaseModel.getMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private MessageReader() {}

  /**
   * Returns what a port with {@code schema} reads from {@code content}: the raw text when {@code
   * schema} accepts a string, unless it restricts strings to enum values and the text names one as
   * a JSON string; otherwise the text parsed as JSON, or the text itself if it is not JSON. A
   * warning naming {@code what} counts the non-text parts it drops.
   */
  static @Nullable Object readValue(Content content, @Nullable Schema schema, String what) {
    long dropped =
        content.parts().orElse(ImmutableList.of()).stream()
            .filter(part -> part.text().isEmpty())
            .count();
    if (dropped > 0) {
      logger.warn("Reading a message as {} dropped {} non-text parts.", what, dropped);
    }
    String text = textOf(content);
    return schema != null && acceptsString(schema)
        ? enumValueOrText(schema, text)
        : jsonOrText(text);
  }

  /**
   * Returns the enum value of {@code schema} that {@code text} spells as a JSON string, such as
   * {@code SHIPPED} for {@code "SHIPPED"}, when {@code schema} does not allow arbitrary strings and
   * {@code text} itself is not already an enum value; otherwise returns {@code text}.
   */
  static String enumValueOrText(Schema schema, String text) {
    // IdentityHashMap keys schemas by identity so shared anyOf DAGs stay O(1) without recursive
    // AutoValue Schema.hashCode/equals.
    if (allowsAnyString(schema, /* inheritedType= */ null, new IdentityHashMap<>())) {
      return text;
    }
    ImmutableSet<String> values = enumValues(schema);
    if (values.isEmpty() || values.contains(text)) {
      return text;
    }
    return readJson(text) instanceof String value && values.contains(value) ? value : text;
  }

  /**
   * Whether {@code schema} accepts an unconstrained string, directly or through an {@code anyOf}.
   */
  private static boolean allowsAnyString(
      Schema schema,
      Type.@Nullable Known inheritedType,
      Map<Schema, Set<Type.@Nullable Known>> visited) {
    Type.Known effectiveType = SchemaFit.declaredType(schema);
    if (effectiveType == null) {
      effectiveType = inheritedType;
    }
    if (!visited.computeIfAbsent(schema, unused -> new HashSet<>()).add(effectiveType)) {
      return false;
    }
    if (schema.enum_().isPresent()) {
      return false;
    }
    if (effectiveType != null && effectiveType != Type.Known.STRING) {
      return false;
    }
    Optional<List<Schema>> anyOf = schema.anyOf();
    if (anyOf.isPresent()) {
      for (Schema branch : anyOf.get()) {
        if (allowsAnyString(branch, effectiveType, visited)) {
          return true;
        }
      }
      return false;
    }
    return effectiveType == Type.Known.STRING || SchemaFit.impliedType(schema) == null;
  }

  /**
   * Returns the enum values of {@code schema} and its {@code anyOf} branches whose effective type
   * is {@code STRING} or unset.
   */
  private static ImmutableSet<String> enumValues(Schema schema) {
    Set<String> values = new HashSet<>();
    collectEnumValues(schema, /* inheritedType= */ null, new IdentityHashMap<>(), values);
    return ImmutableSet.copyOf(values);
  }

  private static void collectEnumValues(
      Schema schema,
      Type.@Nullable Known inheritedType,
      Map<Schema, Set<Type.@Nullable Known>> visited,
      Set<String> values) {
    Type.Known effectiveType = SchemaFit.declaredType(schema);
    if (effectiveType == null) {
      effectiveType = inheritedType;
    }
    if (!visited.computeIfAbsent(schema, unused -> new HashSet<>()).add(effectiveType)) {
      return;
    }
    Optional<List<String>> ownEnum = schema.enum_();
    if (ownEnum.isPresent()) {
      if (effectiveType == Type.Known.STRING || effectiveType == null) {
        values.addAll(ownEnum.get());
      }
      return;
    }
    for (Schema branch : schema.anyOf().orElse(ImmutableList.of())) {
      collectEnumValues(branch, effectiveType, visited, values);
    }
  }

  /** Returns whether {@code schema} accepts a string, directly or through an {@code anyOf}. */
  static boolean acceptsString(Schema schema) {
    return schema.type().map(Type::knownEnum).orElse(null) == Type.Known.STRING
        || schema.anyOf().orElse(ImmutableList.of()).stream()
            .anyMatch(MessageReader::acceptsString);
  }

  /** Returns the text of the non-thought parts of {@code content}. */
  static String textOf(Content content) {
    StringBuilder text = new StringBuilder();
    for (Part part : content.parts().orElse(ImmutableList.of())) {
      if (!part.thought().orElse(false)) {
        part.text().ifPresent(text::append);
      }
    }
    return text.toString();
  }

  /** Returns {@code text} read as JSON, or {@code text} itself if it is not JSON. */
  static @Nullable Object jsonOrText(String text) {
    Object json = readJson(text);
    return json == NOT_JSON ? text : json;
  }

  /**
   * Reads {@code text} as strict JSON into new mutable maps and lists, or returns {@link #NOT_JSON}
   * if it is not JSON. A whole number reads as a {@code Long}, or an exact {@code BigInteger} past
   * the long range, and a number that is not finite makes the text not JSON.
   */
  static @Nullable Object readJson(String text) {
    JsonNode tree;
    try {
      tree = STRICT_JSON.readTree(text);
    } catch (JsonProcessingException e) {
      return NOT_JSON;
    }
    // readTree reads blank text as a missing node instead of failing.
    return tree.isMissingNode() ? NOT_JSON : plainValue(tree);
  }

  /** Returns whether {@code text} is a JSON object, also one {@link #readJson} rejects. */
  static boolean isJsonObject(String text) {
    try {
      return STRICT_JSON.readTree(text).isObject();
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  /**
   * Returns {@code node} as plain Java values, building new maps and lists, or {@link #NOT_JSON} if
   * it holds a number that is not finite.
   */
  private static @Nullable Object plainValue(JsonNode node) {
    if (node.isObject()) {
      Map<String, @Nullable Object> map = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        Object value = plainValue(field.getValue());
        if (value == NOT_JSON) {
          return NOT_JSON;
        }
        map.put(field.getKey(), value);
      }
      return map;
    }
    if (node.isArray()) {
      List<@Nullable Object> list = new ArrayList<>(node.size());
      for (JsonNode item : node) {
        Object value = plainValue(item);
        if (value == NOT_JSON) {
          return NOT_JSON;
        }
        list.add(value);
      }
      return list;
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToLong() ? node.longValue() : node.bigIntegerValue();
    }
    if (node.isNumber()) {
      double number = node.doubleValue();
      return Double.isFinite(number) ? number : NOT_JSON;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    return node.isNull() ? null : node.asText();
  }
}
