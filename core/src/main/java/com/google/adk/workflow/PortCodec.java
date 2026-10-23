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
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.adk.events.Event;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads a {@link FunctionNode} port's input into its type and writes its output in JSON form. An
 * {@code Object} port takes its input as it is, a {@code Content} or {@code Event} port reads its
 * own type, a {@code Void} port ignores its input, and any other port reads through Jackson.
 */
final class PortCodec<T> {

  /**
   * One mapper for every port: unknown properties are ignored, null fields are written, and {@code
   * java.time} values travel as ISO-8601 strings.
   */
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .addModule(new Jdk8Module())
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
          .build();

  private final JavaType type;

  private PortCodec(JavaType type) {
    this.type = type;
  }

  static <T> PortCodec<T> of(Class<T> type) {
    return new PortCodec<>(
        MAPPER.getTypeFactory().constructType(MethodType.methodType(type).wrap().returnType()));
  }

  /** Returns {@code value} read as this port's type; {@code what} names the port in errors. */
  @SuppressWarnings("unchecked") // The checks and Jackson guarantee the type.
  @Nullable T decode(@Nullable Object value, String what) {
    Class<?> raw = type.getRawClass();
    if (raw == Void.class || value == null) {
      return null;
    }
    if (raw == Object.class || hasType(value, type)) {
      return (T) value;
    }
    if (value instanceof Content || value instanceof Event) {
      throw mismatch(what, value.getClass().getSimpleName());
    }
    try {
      if (raw == Content.class) {
        return (T) Content.fromJson(MAPPER.writeValueAsString(value));
      }
      if (raw == Event.class) {
        return (T) Event.fromJson(MAPPER.writeValueAsString(value));
      }
      return MAPPER.convertValue(value, type);
    } catch (RuntimeException | JsonProcessingException e) {
      // Jackson's and genai's messages can quote the value, so no cause.
      throw new NodeInputValidationException(
          "validation error: " + what + " does not match " + type.toCanonical() + ".");
    }
  }

  /**
   * Returns {@code value} in JSON form: maps, lists, {@code Long}, {@code Double}, strings,
   * booleans and nulls.
   *
   * @throws IllegalArgumentException if {@code value} is not of this port's type or has no JSON
   *     form
   */
  @Nullable Object encode(@Nullable Object value, String what) {
    if (value == null) {
      return null;
    }
    if (!type.getRawClass().isInstance(value)) {
      throw new IllegalArgumentException(
          what
              + " expected "
              + type.toCanonical()
              + ", but got "
              + value.getClass().getSimpleName()
              + ".");
    }
    try {
      // genai types serialize through their own mapper, as a session stores them.
      JsonNode tree =
          value instanceof JsonSerializable genai
              ? MAPPER.readTree(genai.toJson())
              : MAPPER.valueToTree(value);
      return jsonNative(tree);
    } catch (IllegalArgumentException | JsonProcessingException e) {
      // Jackson's message can quote the value, so no cause.
      throw new IllegalArgumentException(what + " has no JSON form as " + type.toCanonical() + ".");
    }
  }

  private NodeInputValidationException mismatch(String what, String got) {
    return new NodeInputValidationException(
        "validation error: " + what + " expected " + type.toCanonical() + ", but got " + got + ".");
  }

  /** Whether {@code value} already has {@code type}, down to each element and map entry. */
  private static boolean hasType(Object value, JavaType type) {
    if (!type.getRawClass().isInstance(value)) {
      return false;
    }
    if (type.isCollectionLikeType()) {
      return ((Collection<?>) value)
          .stream().allMatch(item -> item == null || hasType(item, type.getContentType()));
    }
    if (type.isMapLikeType()) {
      return ((Map<?, ?>) value)
          .entrySet().stream()
              .allMatch(
                  entry ->
                      (entry.getKey() == null || hasType(entry.getKey(), type.getKeyType()))
                          && (entry.getValue() == null
                              || hasType(entry.getValue(), type.getContentType())));
    }
    if (type.isArrayType()) {
      return type.getContentType().isPrimitive()
          || Arrays.stream((Object[]) value)
              .allMatch(item -> item == null || hasType(item, type.getContentType()));
    }
    return !type.isContainerType();
  }

  private static @Nullable Object jsonNative(JsonNode node) {
    if (node.isObject()) {
      Map<String, Object> map = new LinkedHashMap<>();
      node.fields()
          .forEachRemaining(field -> map.put(field.getKey(), jsonNative(field.getValue())));
      return map;
    }
    if (node.isArray()) {
      List<Object> list = new ArrayList<>(node.size());
      node.forEach(item -> list.add(jsonNative(item)));
      return list;
    }
    if (node.isIntegralNumber() && node.canConvertToLong()) {
      return node.longValue();
    }
    if (node.isFloat()) {
      // Widening a float adds digits it never had.
      return Double.valueOf(Float.toString(node.floatValue()));
    }
    if (node.isNumber()) {
      return node.doubleValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNull() || node.isMissingNode()) {
      return null;
    }
    return node.asText();
  }
}
