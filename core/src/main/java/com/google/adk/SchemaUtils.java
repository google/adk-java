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

package com.google.adk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Utility class for reading JSON and validating values against schemas. */
public final class SchemaUtils {

  private static final ObjectReader STRICT_JSON =
      JsonBaseModel.getMapper()
          .readerFor(Object.class)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private SchemaUtils() {} // Private constructor for utility class

  /**
   * Matches a value against a schema type.
   *
   * @param value The value to match.
   * @param schema The schema to match against.
   * @param isInput Whether the value is an input or output.
   * @return True if the value matches the schema type, false otherwise.
   * @throws IllegalArgumentException If the schema type is not supported.
   */
  @SuppressWarnings("unchecked") // For tool parameter type casting.
  private static Boolean matchType(Object value, Schema schema, Boolean isInput) {
    // An untyped schema with properties or required keys is an object, one with items is an array,
    // and one with none of them accepts any value, null included.
    Type.Known type =
        schema
            .type()
            .map(Type::knownEnum)
            .orElse(
                schema.properties().isPresent() || schema.required().isPresent()
                    ? Type.Known.OBJECT
                    : schema.items().isPresent() ? Type.Known.ARRAY : null);
    if (value == null) {
      return type == null || schema.nullable().orElse(false);
    }
    if (type == null) {
      return true;
    }
    // Based on types from https://cloud.google.com/vertex-ai/docs/reference/rest/v1/Schema
    switch (type) {
      case STRING:
        return value instanceof String;
      case INTEGER:
        return value instanceof Integer || value instanceof Long;
      case BOOLEAN:
        return value instanceof Boolean;
      case NUMBER:
        return value instanceof Number;
      case ARRAY:
        if (value instanceof List) {
          if (schema.items().isEmpty()) {
            return true;
          }
          for (Object element : (List<?>) value) {
            if (!matchType(element, schema.items().get(), isInput)) {
              return false;
            }
          }
          return true;
        }
        return false;
      case OBJECT:
        if (value instanceof Map) {
          validateMapOnSchema((Map<String, Object>) value, schema, isInput);
          return true;
        } else {
          return false;
        }
      case TYPE_UNSPECIFIED:
        throw new IllegalArgumentException(
            "Unsupported type: " + type + " is not a Open API data type.");
      default:
        break;
    }
    return false;
  }

  /**
   * Validates a map against a schema.
   *
   * @param args The map to validate.
   * @param schema The schema to validate against.
   * @param isInput Whether the map is an input or output.
   * @throws IllegalArgumentException If the map does not match the schema.
   */
  public static void validateMapOnSchema(Map<String, Object> args, Schema schema, Boolean isInput) {
    Preconditions.checkNotNull(isInput, "IsInput cannot be null");
    if (schema.type().map(Type::knownEnum).orElse(Type.Known.OBJECT) != Type.Known.OBJECT) {
      throw new IllegalArgumentException(
          (isInput ? "Input" : "Output") + " schema does not describe an object: " + schema);
    }
    // A schema without properties leaves the keys open, as for a free-form map.
    schema
        .properties()
        .ifPresent(properties -> validateProperties(args, properties, schema, isInput));
    // Check if all required arguments are present.
    if (schema.required().isPresent()) {
      for (String required : schema.required().get()) {
        if (!args.containsKey(required)) {
          if (isInput) {
            throw new IllegalArgumentException("Input args does not contain required " + required);
          } else {
            throw new IllegalArgumentException("Output args does not contain required " + required);
          }
        }
      }
    }
  }

  /** Checks that every key in {@code args} is one of {@code properties} and matches its schema. */
  private static void validateProperties(
      Map<String, Object> args, Map<String, Schema> properties, Schema schema, boolean isInput) {
    for (Entry<String, Object> arg : args.entrySet()) {
      if (!properties.containsKey(arg.getKey())
          || !matchType(arg.getValue(), properties.get(arg.getKey()), isInput)) {
        throw new IllegalArgumentException(
            isInput
                ? "Input arg: " + arg.getKey() + " does not match agent input schema: " + schema
                : "Output arg: " + arg.getKey() + " does not match agent output schema: " + schema);
      }
    }
  }

  /**
   * Parses model text as one JSON value against {@code schema}, or returns the text itself if that
   * fails and the schema accepts a string. Returns {@code null} for blank text or a JSON null the
   * schema allows, and fills omitted properties of a parsed value with their schema defaults.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @throws IllegalArgumentException if the text does not match the schema
   */
  public static @Nullable Object parseModelOutput(String output, Schema schema) {
    if (output.isBlank()) {
      return null;
    }
    String reason;
    try {
      Object value = readStrictJson(output);
      reason = mismatch(value, schema);
      if (reason == null) {
        return fillDefaults(value, schema);
      }
    } catch (JsonProcessingException e) {
      reason = "it is not JSON";
    }
    if (acceptsString(schema)) {
      return output;
    }
    // The reason names only parts of the schema, never the model's output.
    throw new IllegalArgumentException(
        "validation error: the output does not match its schema; " + reason + ".");
  }

  /** Returns whether {@code schema} accepts a string, directly or through an {@code anyOf}. */
  private static boolean acceptsString(Schema schema) {
    return typeOf(schema) == Type.Known.STRING
        || schema.anyOf().orElse(ImmutableList.of()).stream().anyMatch(SchemaUtils::acceptsString);
  }

  /**
   * Reads {@code text} as one JSON value, failing on trailing text.
   *
   * @throws JsonProcessingException if the text is not one JSON value
   */
  private static @Nullable Object readStrictJson(String text) throws JsonProcessingException {
    return STRICT_JSON.readValue(text);
  }

  /**
   * Returns why {@code value} does not match {@code schema}, enum values included, or empty if it
   * does. The reason names parts of the schema but never the value, which comes from the user.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public static Optional<String> findMismatch(@Nullable Object value, Schema schema) {
    return Optional.ofNullable(mismatch(value, schema, /* strict= */ true));
  }

  /**
   * Returns whether {@code schema} is an object: typed {@code OBJECT}, or without a known type
   * unless it has {@code items} and neither {@code properties} nor {@code required}.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public static boolean describesObject(Schema schema) {
    Type.Known type = schema.type().map(Type::knownEnum).orElse(Type.Known.TYPE_UNSPECIFIED);
    if (type != Type.Known.TYPE_UNSPECIFIED) {
      return type == Type.Known.OBJECT;
    }
    return schema.items().isEmpty()
        || schema.properties().isPresent()
        || schema.required().isPresent();
  }

  /**
   * Parses {@code output} and validates it against {@code schema}. An object schema goes through
   * {@link #validateOutputSchema}; for an array or primitive schema this returns the parsed value,
   * such as a list, which may be null.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @throws IllegalArgumentException If the output does not match the schema.
   * @throws JsonProcessingException If the output cannot be parsed.
   */
  public static @Nullable Object validateOutput(String output, Schema schema)
      throws JsonProcessingException {
    if (describesObject(schema)) {
      return validateOutputSchema(output, schema);
    }
    Object value = readStrictJson(output);
    String reason = mismatch(value, schema);
    if (reason != null) {
      // The reason names only parts of the schema, never the model's output.
      throw new IllegalArgumentException(
          "validation error: the output does not match its schema; " + reason + ".");
    }
    return value;
  }

  /**
   * Validates an output string against a schema.
   *
   * @param output The output string to validate.
   * @param schema The schema to validate against.
   * @return The output map.
   * @throws IllegalArgumentException If the output string does not match the schema.
   * @throws JsonProcessingException If the output string cannot be parsed.
   */
  @SuppressWarnings("unchecked") // For tool parameter type casting.
  public static Map<String, Object> validateOutputSchema(String output, Schema schema)
      throws JsonProcessingException {
    Map<String, Object> outputMap = JsonBaseModel.getMapper().readValue(output, HashMap.class);
    validateMapOnSchema(outputMap, schema, false);
    return outputMap;
  }

  /**
   * Returns {@code args} with defaults added for omitted (not explicitly null) properties across
   * nested objects, array items, and the first matching {@code anyOf} alternative. The result may
   * be {@code args} itself or share its maps and lists; this method does not validate the result or
   * the defaults.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @SuppressWarnings("unchecked") // A map stays a map with String keys.
  public static Map<String, Object> withDefaults(Map<String, Object> args, Schema schema) {
    return (Map<String, Object>) fillDefaults(Preconditions.checkNotNull(args), schema);
  }

  /**
   * Returns {@code value}, any JSON value, as {@link #withDefaults} fills it; null stays null.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public static @Nullable Object valueWithDefaults(@Nullable Object value, Schema schema) {
    return fillDefaults(value, schema);
  }

  private static @Nullable Object fillDefaults(@Nullable Object value, Schema schema) {
    if (value instanceof Map<?, ?> map && schema.properties().isPresent()) {
      Map<String, Schema> properties = schema.properties().get();
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Schema property = properties.get(key);
        result.put(
            key, property == null ? entry.getValue() : fillDefaults(entry.getValue(), property));
      }
      for (Map.Entry<String, Schema> property : properties.entrySet()) {
        // An explicit null is a value, so only an absent key gets the default.
        Optional<Object> fallback = property.getValue().default_();
        if (fallback.isPresent() && !result.containsKey(property.getKey())) {
          result.put(property.getKey(), copyDefault(fallback.get()));
        }
      }
      return result;
    }
    if (value instanceof List<?> list && schema.items().isPresent()) {
      Schema items = schema.items().get();
      List<Object> result = new ArrayList<>();
      for (Object item : list) {
        result.add(fillDefaults(item, items));
      }
      return result;
    }
    if (value instanceof Map<?, ?> || value instanceof List<?>) {
      // Apply defaults from the first alternative the value fits.
      for (Schema member : schema.anyOf().orElse(ImmutableList.of())) {
        if (fits(value, member)) {
          return fillDefaults(value, member);
        }
      }
    }
    return value;
  }

  /**
   * Returns whether {@code value} can receive defaults from {@code schema}. In addition to {@link
   * #mismatch}, it checks enum values, nested {@code anyOf} alternatives, and types inferred from
   * keys, including items of an untyped array.
   */
  private static boolean fits(@Nullable Object value, Schema schema) {
    if (value == null && schema.nullable().orElse(false)) {
      return true;
    }
    Type.Known type = inferredTypeOf(schema);
    boolean kindFits =
        type == null
            || (value instanceof Map<?, ?>
                ? type == Type.Known.OBJECT
                : value instanceof List<?>
                    ? type == Type.Known.ARRAY
                    : type != Type.Known.OBJECT && type != Type.Known.ARRAY);
    if (!kindFits || mismatch(value, schema) != null) {
      return false;
    }
    Optional<List<String>> allowed = schema.enum_();
    if (allowed.isPresent() && (value == null || !allowed.get().contains(String.valueOf(value)))) {
      return false;
    }
    List<Schema> anyOf = schema.anyOf().orElse(ImmutableList.of());
    if (!anyOf.isEmpty() && anyOf.stream().noneMatch(member -> fits(value, member))) {
      return false;
    }
    if (value instanceof Map<?, ?> map && schema.properties().isPresent()) {
      Map<String, Schema> properties = schema.properties().get();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Schema property = properties.get(String.valueOf(entry.getKey()));
        if (property != null && !fits(entry.getValue(), property)) {
          return false;
        }
      }
    }
    if (value instanceof List<?> list && schema.items().isPresent()) {
      Schema items = schema.items().get();
      for (Object item : list) {
        if (!fits(item, items)) {
          return false;
        }
      }
    }
    return true;
  }

  /** Returns a copy of {@code value} whose maps and lists are new, so callers can change it. */
  private static @Nullable Object copyDefault(@Nullable Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        copy.put(String.valueOf(entry.getKey()), copyDefault(entry.getValue()));
      }
      return copy;
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>();
      for (Object item : list) {
        copy.add(copyDefault(item));
      }
      return copy;
    }
    return value;
  }

  private static @Nullable String mismatch(@Nullable Object value, Schema schema) {
    return mismatch(value, schema, /* strict= */ false);
  }

  /**
   * Returns why {@code value} does not match. If {@code strict}, it also checks enum values at any
   * depth and the items of a list under an untyped schema, as a workflow node's schema check does.
   */
  private static @Nullable String mismatch(@Nullable Object value, Schema schema, boolean strict) {
    if (value == null && schema.nullable().orElse(false)) {
      return null;
    }
    Optional<List<String>> allowed = schema.enum_();
    if (strict
        && allowed.isPresent()
        && (value == null || !allowed.get().contains(String.valueOf(value)))) {
      return "it is not one of the schema's enum values";
    }
    // Check maps before anyOf so each member is validated as an object with its required keys.
    if (value instanceof Map<?, ?> map) {
      return mapMismatch(map, schema, strict);
    }
    List<Schema> anyOf = schema.anyOf().orElse(ImmutableList.of());
    if (!anyOf.isEmpty()
        && anyOf.stream().allMatch(member -> mismatch(value, member, strict) != null)) {
      return "it matches none of the schema's anyOf alternatives";
    }
    Type.Known type = typeOf(schema);
    if (type == null || type == Type.Known.TYPE_UNSPECIFIED) {
      Schema itemSchema = schema.items().orElse(null);
      return strict
              && itemSchema != null
              && value instanceof Collection<?> items
              && items.stream().anyMatch(item -> mismatch(item, itemSchema, strict) != null)
          ? "an item does not match the schema's items"
          : null;
    }
    boolean matches =
        switch (type) {
          case STRING -> value instanceof String;
          case INTEGER ->
              value instanceof Integer
                  || value instanceof Long
                  || value instanceof Short
                  || value instanceof Byte
                  || value instanceof BigInteger;
          case NUMBER -> value instanceof Number;
          case BOOLEAN -> value instanceof Boolean;
          case NULL -> value == null;
          case ARRAY -> {
            if (!(value instanceof Collection<?> items)) {
              yield false;
            }
            Optional<Schema> itemSchema = schema.items();
            yield itemSchema.isEmpty()
                || items.stream()
                    .allMatch(item -> mismatch(item, itemSchema.get(), strict) == null);
          }
          case OBJECT -> false;
          default -> true;
        };
    return matches ? null : "it is not of the schema's type " + type;
  }

  /** Returns why {@code map} does not match {@code schema}, or {@code null} if it does. */
  private static @Nullable String mapMismatch(Map<?, ?> map, Schema schema, boolean strict) {
    Type.Known type = typeOf(schema);
    if (type != null && type != Type.Known.OBJECT && type != Type.Known.TYPE_UNSPECIFIED) {
      return "the schema's type is " + type + ", not an object";
    }
    List<Schema> anyOf = schema.anyOf().orElse(ImmutableList.of());
    if (!anyOf.isEmpty()
        && anyOf.stream().allMatch(member -> mismatch(map, member, strict) != null)) {
      return "it matches none of the schema's anyOf alternatives";
    }
    Optional<Map<String, Schema>> properties = schema.properties();
    if (properties.isPresent()) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Schema property = properties.get().get(String.valueOf(entry.getKey()));
        // An undeclared key comes from the user, so the reason does not name it.
        if (property == null) {
          return "it has a key the schema does not declare";
        }
        if (mismatch(entry.getValue(), property, strict) != null) {
          return "property '" + entry.getKey() + "' does not match its schema";
        }
      }
    }
    for (String required : schema.required().orElse(ImmutableList.of())) {
      if (!map.containsKey(required)) {
        return "required property '" + required + "' is missing";
      }
    }
    return null;
  }

  private static Type.@Nullable Known typeOf(Schema schema) {
    return schema.type().map(Type::knownEnum).orElse(null);
  }

  /** Returns the type of {@code schema}, inferred from keys if unset or TYPE_UNSPECIFIED. */
  private static Type.@Nullable Known inferredTypeOf(Schema schema) {
    Type.Known type = typeOf(schema);
    if (type != null && type != Type.Known.TYPE_UNSPECIFIED) {
      return type;
    }
    if (schema.properties().isPresent() || schema.required().isPresent()) {
      return Type.Known.OBJECT;
    }
    return schema.items().isPresent() ? Type.Known.ARRAY : null;
  }
}
