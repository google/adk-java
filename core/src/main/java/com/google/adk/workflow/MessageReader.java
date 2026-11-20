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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
   * Returns what a port with {@code schema} reads from {@code content}: the text if the schema
   * accepts a string, or an enum value the text holds as a JSON string, else the text's JSON, or
   * the text if it is not JSON. A warning naming {@code what} counts the non-text parts it drops.
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
   * Returns {@code text}, or the enum value of {@code schema} that it holds as a JSON string, as
   * ADK Python reads an enum.
   */
  static String enumValueOrText(Schema schema, String text) {
    ImmutableSet<String> values = enumValues(schema).collect(toImmutableSet());
    if (values.isEmpty() || values.contains(text)) {
      return text;
    }
    return readJson(text) instanceof String value && values.contains(value) ? value : text;
  }

  /** Returns the enum values of {@code schema}'s string types, itself or in an {@code anyOf}. */
  private static Stream<String> enumValues(Schema schema) {
    Stream<String> own =
        schema.type().map(Type::knownEnum).orElse(null) == Type.Known.STRING
            ? schema.enum_().orElse(ImmutableList.of()).stream()
            : Stream.empty();
    return Stream.concat(
        own, schema.anyOf().orElse(ImmutableList.of()).stream().flatMap(MessageReader::enumValues));
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
