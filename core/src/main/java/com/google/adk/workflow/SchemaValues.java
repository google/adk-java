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

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Schema;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Validates values against workflow schemas and returns session form so live and resumed runs see
 * the same types and mutability. {@link #check} encodes twice around {@link ValueCoercer} because
 * each pass has a different job:
 *
 * <ol>
 *   <li>JSON form: encodes non-JSON values (records, enums) for {@link ValueCoercer}, keeping
 *       {@link Content} intact so the schema reads text or JSON.
 *   <li>Validate: runs {@link ValueCoercer} to check the schema and convert literals and messages.
 *   <li>Session form: encodes the mutable result into unmodifiable collections, {@code Integer} (or
 *       {@code Long}, {@code BigInteger}) for integral JSON numbers, else finite {@code Double}.
 * </ol>
 *
 * <pre>{@code
 * Content hi = Content.fromParts(Part.fromText("hi"));
 * Map.of("msg", hi, "status", Status.OPEN)  // input under {msg: STRING, status: STRING}
 * {msg=hi, status="OPEN"}    // 1. JSON form: enum to name, message kept
 * {msg="hi", status="OPEN"}  // 2. ValueCoercer: message read as text, mutable map
 * {msg="hi", status="OPEN"}  // 3. session form: unmodifiable map
 * }</pre>
 *
 * <p>Failures throw {@link IllegalArgumentException} starting with {@code "validation error: "},
 * naming {@code what} and never the value; callers wrap as needed, such as in {@link
 * NodeInputValidationException}.
 */
final class SchemaValues {

  /** Writes any value in JSON form, as a {@link FunctionNode} port of type {@code Object} does. */
  private static final PortCodec<Object> OBJECT_PORT =
      PortCodec.output(PortCodec.javaType(Object.class), "a value of type Object");

  private SchemaValues() {}

  /**
   * Returns {@code value} checked against {@code schema}, in the form a stored session reads back.
   * {@code what} names the value in errors, such as {@code "output of node 'review'"} or {@code
   * "state key 'count'"}.
   *
   * <pre>{@code
   * // schema: {count: INTEGER}
   * check(Map.of("count", "3"), schema, "output of node 'n'");  // unmodifiable {count=3}, Integer
   * check(Map.of("count", "x"), schema, "output of node 'n'");
   * // IllegalArgumentException: validation error: output of node 'n' does not match its schema:
   * // 'count' is not an integer
   * }</pre>
   *
   * <p>Null is checked like any other value: it passes only a schema that takes null, and comes
   * back as null. A caller for which null means "no value", such as a node input, skips the call.
   * An {@link Event} is checked as it is and comes back as it is, so only a schema that names no
   * type accepts it.
   *
   * @throws IllegalArgumentException if the value has no JSON form or does not match {@code schema}
   */
  static @Nullable Object check(@Nullable Object value, Schema schema, String what) {
    Object checked = ValueCoercer.coerceValue(jsonForm(value, what), schema, what);
    // The coercer returns mutable copies and a Long for a converted integer.
    return checked instanceof Event ? checked : sessionForm(checked, what, /* nullable= */ false);
  }

  /**
   * Returns {@code value} in the JSON form that {@link #check} checks. A value that {@link
   * #isJsonForm} and an {@link Event} come back as they are; in a map with string keys or a
   * collection only the other parts are written, so a message there stays a {@link Content}. Other
   * values are written in session form, a record keeping its null fields.
   *
   * <pre>{@code
   * record Named(String name, @Nullable String nickname) {}
   * jsonForm(new Named("Ann", null), what);                // {name="Ann", nickname=null}
   * jsonForm(Map.of("msg", content, "tag", Tag.A), what);  // {msg=<content>, tag="A"}
   * jsonForm(Map.of("n", 3L), what);                       // the same map instance
   * }</pre>
   *
   * @throws IllegalArgumentException if a part it writes has no JSON form or holds a NaN or
   *     infinite number
   */
  private static @Nullable Object jsonForm(@Nullable Object value, String what) {
    return value instanceof Event || isJsonForm(value)
        ? value
        : toJson(value, what, /* isPart= */ false);
  }

  /**
   * Whether {@code value} is in JSON form: null, a string, a boolean, a number that {@link
   * ValueCoercer} reads, a message, or a map with string keys or a collection of such values. A
   * message counts, since the schema decides how it reads. The JSON form step returns such a value
   * as it is.
   *
   * <pre>{@code
   * isJsonForm(Map.of("n", 3L, "tags", List.of("a")));  // true
   * isJsonForm(Map.of("status", Status.OPEN));          // false: an enum
   * isJsonForm(new BigDecimal("2.5"));                  // false: written as a Double
   * }</pre>
   */
  static boolean isJsonForm(@Nullable Object value) {
    return isJsonLeaf(value)
        || (value instanceof Map<?, ?> map
            && map.entrySet().stream()
                .allMatch(
                    entry -> entry.getKey() instanceof String && isJsonForm(entry.getValue())))
        || (value instanceof Collection<?> items
            && items.stream().allMatch(SchemaValues::isJsonForm));
  }

  /** Whether {@code value} is a JSON value with no parts, counting a message as one. */
  private static boolean isJsonLeaf(@Nullable Object value) {
    return value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Content
        // The number types the coercer reads; others, such as BigDecimal, are written as JSON.
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger
        || value instanceof Double
        || value instanceof Float;
  }

  /**
   * Returns {@code value} in JSON form, keeping its JSON parts as they are. A part of a map or
   * collection that writes null, such as an empty {@code OptionalInt}, becomes null.
   */
  private static @Nullable Object toJson(@Nullable Object value, String what, boolean isPart) {
    if (isJsonLeaf(value)) {
      return value;
    }
    if (value instanceof Map<?, ?> map
        && map.keySet().stream().allMatch(key -> key instanceof String)) {
      Map<String, @Nullable Object> json = new LinkedHashMap<>();
      map.forEach((key, item) -> json.put((String) key, partToJson(item, what)));
      return json;
    }
    if (value instanceof Collection<?> items) {
      List<@Nullable Object> json = new ArrayList<>(items.size());
      items.forEach(item -> json.add(partToJson(item, what)));
      return json;
    }
    return sessionForm(value, what, /* nullable= */ isPart);
  }

  /** Returns a part of a map or collection in JSON form. */
  private static @Nullable Object partToJson(@Nullable Object part, String what) {
    // Unwrapped first, so a present message stays a message.
    return part instanceof Optional<?> optional
        ? partToJson(optional.orElse(null), what)
        : toJson(part, what, /* isPart= */ true);
  }

  /**
   * Returns {@code value} in session form, failing with a validation error. Only when {@code
   * nullable} may a value that writes null come back as null.
   */
  private static @Nullable Object sessionForm(
      @Nullable Object value, String what, boolean nullable) {
    try {
      return nullable ? OBJECT_PORT.encodeNullable(value, what) : OBJECT_PORT.encode(value, what);
    } catch (IllegalArgumentException e) {
      // The port's message names what and a type, never the value.
      throw new IllegalArgumentException("validation error: " + e.getMessage(), e);
    }
  }
}
