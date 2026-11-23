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

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Checks a value against a {@link Schema}'s types, enums, keys and nullability, ignoring formats,
 * patterns and bounds, and returns it converted to the schema's types. A quoted number or boolean
 * converts, as does a whole number such as {@code 3.0} for an integer, and a message reads as JSON
 * unless the schema takes a string or the text is not JSON. Undeclared keys are kept, since a
 * {@link Schema} cannot close an object.
 */
final class ValueCoercer {

  /** How many {@code anyOf} alternatives one check may try, since nested unions multiply out. */
  private static final int MAX_UNION_CHECKS = 1000;

  /**
   * Returns {@code value} converted to {@code schema}'s types.
   *
   * @throws IllegalArgumentException if it does not match; the message names {@code what} and parts
   *     of the schema, never the value
   */
  static @Nullable Object coerceValue(@Nullable Object value, Schema schema, String what) {
    ValueCoercer coercer = new ValueCoercer(what);
    try {
      return coercer.coerce(value, schema, "");
    } catch (Mismatch e) {
      throw coercer.failure(e.getMessage());
    }
  }

  private final String what;
  private int unionChecksLeft = MAX_UNION_CHECKS;

  private ValueCoercer(String what) {
    this.what = what;
  }

  private IllegalArgumentException failure(String reason) {
    return new IllegalArgumentException(
        "validation error: " + what + " does not match its schema: " + reason);
  }

  private @Nullable Object coerce(@Nullable Object value, Schema schema, String path) {
    Object data =
        value instanceof Content content ? MessageReader.readValue(content, schema, what) : value;
    if (data == null) {
      return coerceNull(schema, path);
    }
    Object matched =
        schema.anyOf().isPresent() ? coerceAnyOf(data, schema.anyOf().get(), path) : data;
    Object result = coerceByType(Objects.requireNonNull(matched), schema, path);
    if (schema.enum_().isPresent() && !schema.enum_().get().contains(String.valueOf(result))) {
      throw new Mismatch(at(path) + "is not one of the schema's enum values");
    }
    return result;
  }

  /**
   * Returns null if a nullable schema, a NULL type or an untyped schema without an enum takes it.
   */
  private @Nullable Object coerceNull(Schema schema, String path) {
    if (schema.nullable().orElse(false)) {
      return null;
    }
    // Throws unless an alternative takes null, which it then reads as null.
    Object coerced =
        schema.anyOf().isPresent() ? coerceAnyOf(null, schema.anyOf().get(), path) : null;
    Type.Known type = SchemaFit.declaredType(schema);
    if (type != Type.Known.NULL && (type != null || schema.enum_().isPresent())) {
      throw new Mismatch(at(path) + "is null");
    }
    return coerced;
  }

  private Object coerceByType(Object value, Schema schema, String path) {
    Type.Known type = SchemaFit.declaredType(schema);
    if (type == null) {
      // Keys and items constrain a map or a list even when the schema names no type.
      if (value instanceof Map<?, ?> map && SchemaFit.declaresKeys(schema)) {
        return coerceObject(map, schema, path);
      }
      if (value instanceof Collection<?> items && schema.items().isPresent()) {
        return coerceArray(items, schema.items().get(), path);
      }
      return value;
    }
    Object coerced =
        switch (type) {
          case STRING -> value instanceof String ? value : null;
          case INTEGER -> integerValue(value);
          case NUMBER -> numberValue(value);
          case BOOLEAN -> PortScalars.booleanLiteral(value);
          case ARRAY ->
              value instanceof Collection<?> items
                  ? coerceArray(items, schema.items().orElse(null), path)
                  : null;
          case OBJECT -> value instanceof Map<?, ?> map ? coerceObject(map, schema, path) : null;
          case NULL, TYPE_UNSPECIFIED -> null;
        };
    if (coerced == null) {
      throw new Mismatch(at(path) + "is not " + typeName(type));
    }
    return coerced;
  }

  /** Returns {@code value} if an alternative takes it as it is, else the first conversion. */
  private @Nullable Object coerceAnyOf(
      @Nullable Object value, List<Schema> alternatives, String path) {
    Object converted = null;
    for (Schema alternative : alternatives) {
      if (unionChecksLeft == 0) {
        // Not a Mismatch, so no enclosing alternative swallows it.
        throw failure(
            "it tried more than "
                + MAX_UNION_CHECKS
                + " union alternatives, so its `anyOf` branches multiply out beyond what can be"
                + " checked");
      }
      unionChecksLeft--;
      Object coerced;
      try {
        coerced = coerce(value, alternative, path);
      } catch (Mismatch rejected) {
        continue; // This alternative rejects the value; try the next one.
      }
      if (Objects.equals(coerced, value)) {
        return coerced;
      }
      if (converted == null) {
        converted = coerced;
      }
    }
    if (converted == null) {
      throw new Mismatch(at(path) + "matches none of the schema's alternatives");
    }
    return converted;
  }

  private List<@Nullable Object> coerceArray(
      Collection<?> items, @Nullable Schema itemSchema, String path) {
    if (itemSchema == null) {
      return new ArrayList<>(items);
    }
    List<@Nullable Object> result = new ArrayList<>(items.size());
    int index = 0;
    for (Object item : items) {
      // Each item gets its own union budget: a list's length is data, not schema complexity.
      result.add(new ValueCoercer(what).coerce(item, itemSchema, path + "[" + index + "]"));
      index++;
    }
    return result;
  }

  private Map<String, @Nullable Object> coerceObject(Map<?, ?> map, Schema schema, String path) {
    Map<String, Schema> properties = schema.properties().orElse(ImmutableMap.of());
    Map<String, @Nullable Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String name = String.valueOf(entry.getKey());
      Schema property = properties.get(name);
      // An undeclared key is user data, so it is kept unchecked and never named in a message.
      result.put(
          name,
          property == null
              ? entry.getValue()
              : coerce(entry.getValue(), property, path + "." + name));
    }
    for (String name : schema.required().orElse(ImmutableList.of())) {
      if (!result.containsKey(name)) {
        throw new Mismatch(at(path) + "lacks required '" + name + "'");
      }
    }
    return result;
  }

  /** Returns {@code value} as an integer; an integral number keeps its type. */
  private static @Nullable Object integerValue(Object value) {
    // INTEGER sets no width, so any BigInteger fits; narrower ports check range when decoding.
    if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger) {
      return value;
    }
    return value instanceof Double || value instanceof Float || value instanceof String
        ? PortScalars.integerLiteral(value)
        : null;
  }

  /** Returns {@code value} as a finite number, keeping the type of one that is a number. */
  private static @Nullable Object numberValue(Object value) {
    if (value instanceof Double || value instanceof Float) {
      return Double.isFinite(((Number) value).doubleValue()) ? value : null;
    }
    return value instanceof Number ? value : PortScalars.numberLiteral(value);
  }

  private static String at(String path) {
    return path.isEmpty()
        ? "the value "
        : "'" + path.substring(path.startsWith(".") ? 1 : 0) + "' ";
  }

  private static String typeName(Type.Known type) {
    return switch (type) {
      case INTEGER -> "an integer";
      case ARRAY -> "a list";
      case OBJECT -> "an object";
      default -> "a " + Ascii.toLowerCase(type.name());
    };
  }

  /** A mismatch whose message names a place in the value and parts of the schema, never data. */
  private static final class Mismatch extends RuntimeException {
    Mismatch(String message) {
      // Thrown for every rejected anyOf alternative, so it skips the stack trace.
      super(
          message,
          /* cause= */ null,
          /* enableSuppression= */ false,
          /* writableStackTrace= */ false);
    }
  }
}
