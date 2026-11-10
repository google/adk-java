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
import com.google.common.base.Preconditions;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** Utility class for validating schemas. */
public final class SchemaUtils {

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
}
