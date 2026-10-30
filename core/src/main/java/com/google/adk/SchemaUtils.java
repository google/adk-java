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
import java.util.Collection;
import java.util.HashMap;
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
    if (value == null) {
      return schema.nullable().orElse(false);
    }
    // Based on types from https://cloud.google.com/vertex-ai/docs/reference/rest/v1/Schema
    Type.Known type = schema.type().get().knownEnum();
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
    Map<String, Schema> properties = schema.properties().get();
    for (Entry<String, Object> arg : args.entrySet()) {
      // Check if the argument is in the schema.
      if (!properties.containsKey(arg.getKey())) {
        if (isInput) {
          throw new IllegalArgumentException(
              "Input arg: " + arg.getKey() + " does not match agent input schema: " + schema);
        } else {
          throw new IllegalArgumentException(
              "Output arg: " + arg.getKey() + " does not match agent output schema: " + schema);
        }
      }
      // Check if the argument type matches the schema type.
      if (!matchType(arg.getValue(), properties.get(arg.getKey()), isInput)) {
        if (isInput) {
          throw new IllegalArgumentException(
              "Input arg: " + arg.getKey() + " does not match agent input schema: " + schema);
        } else {
          throw new IllegalArgumentException(
              "Output arg: " + arg.getKey() + " does not match agent output schema: " + schema);
        }
      }
    }
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

  /**
   * Parses model text against {@code schema} as one JSON value, or returns the text itself when
   * that fails and the schema accepts a string. Blank text, and a JSON null the schema allows, give
   * {@code null}.
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
        return value;
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

  /**
   * Returns whether {@code schema} accepts a string, directly or through an {@code anyOf}.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public static boolean acceptsString(Schema schema) {
    return typeOf(schema) == Type.Known.STRING
        || schema.anyOf().orElse(ImmutableList.of()).stream().anyMatch(SchemaUtils::acceptsString);
  }

  /**
   * Reads {@code text} as one JSON value, failing on trailing text.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @throws JsonProcessingException if the text is not one JSON value
   */
  public static @Nullable Object readStrictJson(String text) throws JsonProcessingException {
    return STRICT_JSON.readValue(text);
  }

  /**
   * Returns why {@code value} does not match {@code schema}, or empty if it does. The reason names
   * parts of the schema but never the value, which comes from the user.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public static Optional<String> findMismatch(@Nullable Object value, Schema schema) {
    return Optional.ofNullable(mismatch(value, schema));
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

  private static @Nullable String mismatch(@Nullable Object value, Schema schema) {
    if (value == null && schema.nullable().orElse(false)) {
      return null;
    }
    // Check maps before anyOf so each member is validated as an object with its required keys.
    if (value instanceof Map<?, ?> map) {
      return mapMismatch(map, schema);
    }
    List<Schema> anyOf = schema.anyOf().orElse(ImmutableList.of());
    if (!anyOf.isEmpty() && anyOf.stream().allMatch(member -> mismatch(value, member) != null)) {
      return "it matches none of the schema's anyOf alternatives";
    }
    Type.Known type = typeOf(schema);
    if (type == null || type == Type.Known.TYPE_UNSPECIFIED) {
      return null;
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
                || items.stream().allMatch(item -> mismatch(item, itemSchema.get()) == null);
          }
          case OBJECT -> false;
          default -> true;
        };
    return matches ? null : "it is not of the schema's type " + type;
  }

  /** Returns why {@code map} does not match {@code schema}, or {@code null} if it does. */
  private static @Nullable String mapMismatch(Map<?, ?> map, Schema schema) {
    Type.Known type = typeOf(schema);
    if (type != null && type != Type.Known.OBJECT && type != Type.Known.TYPE_UNSPECIFIED) {
      return "the schema's type is " + type + ", not an object";
    }
    List<Schema> anyOf = schema.anyOf().orElse(ImmutableList.of());
    if (!anyOf.isEmpty() && anyOf.stream().allMatch(member -> mapMismatch(map, member) != null)) {
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
        if (mismatch(entry.getValue(), property) != null) {
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
}
