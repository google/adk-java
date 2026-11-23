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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.ObjectArrayDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.primitives.Longs;
import com.google.common.primitives.Primitives;
import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Number, boolean and char readers, also for primitive arrays, map keys and {@code OptionalInt},
 * {@code OptionalLong} and {@code OptionalDouble}. Each accepts a value's own JSON type, the same
 * value quoted, and for an integer type a whole number such as {@code "1e2"}; Jackson's coercion
 * configs cannot express this. {@code BigInteger}, {@code BigDecimal}, {@code Number} and the
 * {@code Atomic} types keep Jackson's readers.
 */
final class PortScalars {

  // The strict JSON number grammar, the same as ADK Kotlin's SchemaUtils.JSON_NUMBER.
  private static final Pattern JSON_NUMBER =
      Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

  private PortScalars() {}

  /** Returns the module that registers the readers. */
  static SimpleModule module() {
    SimpleModule module = new SimpleModule("PortScalars");
    add(module, Long.class, long.class, 0L, PortScalars::integerLiteral);
    add(module, Integer.class, int.class, 0, whole(n -> (int) n));
    add(module, Short.class, short.class, (short) 0, whole(n -> (short) n));
    add(module, Byte.class, byte.class, (byte) 0, whole(n -> (byte) n));
    add(module, Double.class, double.class, 0.0, PortScalars::doubleLiteral);
    add(module, Float.class, float.class, 0f, PortScalars::floatLiteral);
    add(module, Boolean.class, boolean.class, false, PortScalars::booleanLiteral);
    add(module, Character.class, char.class, '\0', PortScalars::charLiteral);
    // byte[] reads base64 and char[] text, as Jackson writes them.
    addArray(module, long[].class);
    addArray(module, int[].class);
    addArray(module, short[].class);
    addArray(module, double[].class);
    addArray(module, float[].class);
    addArray(module, boolean[].class);
    addOptional(
        module, OptionalInt.class, whole(n -> (int) n), OptionalInt::of, OptionalInt.empty());
    addOptional(
        module,
        OptionalLong.class,
        PortScalars::integerLiteral,
        OptionalLong::of,
        OptionalLong.empty());
    addOptional(
        module,
        OptionalDouble.class,
        PortScalars::doubleLiteral,
        OptionalDouble::of,
        OptionalDouble.empty());
    return module;
  }

  private static <T> void add(
      SimpleModule module,
      Class<T> boxed,
      Class<T> primitive,
      T defaultValue,
      Function<@Nullable Object, @Nullable T> reader) {
    module.addDeserializer(boxed, new ScalarDeserializer<>(boxed, defaultValue, reader));
    module.addDeserializer(primitive, new ScalarDeserializer<>(primitive, defaultValue, reader));
    module.addKeyDeserializer(boxed, new ScalarKeyDeserializer(boxed, reader));
  }

  private static <T, E> void addOptional(
      SimpleModule module,
      Class<T> type,
      Function<@Nullable Object, @Nullable E> reader,
      Function<E, T> wrap,
      T empty) {
    module.addDeserializer(type, new PrimitiveOptionalDeserializer<>(type, reader, wrap, empty));
  }

  private static <A> void addArray(SimpleModule module, Class<A> type) {
    module.addDeserializer(type, new ArrayDeserializer<>(type));
  }

  /** Reads a whole number that {@code narrow} keeps unchanged, so one in its type's range. */
  private static <T extends Number> Function<@Nullable Object, @Nullable T> whole(
      LongFunction<T> narrow) {
    return v -> {
      Long n = integerLiteral(v);
      if (n == null) {
        return null;
      }
      T value = narrow.apply(n);
      return value.longValue() == n ? value : null;
    };
  }

  private static @Nullable Double doubleLiteral(@Nullable Object value) {
    Number n = numberLiteral(value);
    return n == null ? null : n.doubleValue();
  }

  private static @Nullable Float floatLiteral(@Nullable Object value) {
    Number n = numberLiteral(value);
    return n == null || !Float.isFinite(n.floatValue()) ? null : n.floatValue();
  }

  /**
   * Returns {@code value} as a long if it is a whole number in the long range or a JSON literal of
   * one, else null. A fraction reads as a double first, so digits past its precision round away.
   */
  static @Nullable Long integerLiteral(@Nullable Object value) {
    Number n = numberLiteral(value);
    if (n == null) {
      return null;
    }
    return n instanceof Long l ? l : wholeLong(n.doubleValue());
  }

  /** Returns {@code value} as a finite number if it is one or a JSON literal of one, else null. */
  static @Nullable Number numberLiteral(@Nullable Object value) {
    if (value instanceof Long n) {
      return n;
    }
    if (value instanceof BigInteger n && n.bitLength() < Long.SIZE) {
      return n.longValue();
    }
    if (value instanceof Number n) {
      return Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }
    if (value instanceof String text && JSON_NUMBER.matcher(text).matches()) {
      Long n = Longs.tryParse(text);
      if (n != null) {
        return n;
      }
      double d = Double.parseDouble(text);
      return Double.isFinite(d) ? d : null;
    }
    return null;
  }

  /** Returns {@code value} as a boolean if it is one or the text true or false, else null. */
  static @Nullable Boolean booleanLiteral(@Nullable Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String text) {
      return switch (text) {
        case "true" -> true;
        case "false" -> false;
        default -> null;
      };
    }
    return null;
  }

  /** Returns {@code value} as a char if it is text of one character; Jackson takes a code point. */
  private static @Nullable Character charLiteral(@Nullable Object value) {
    return value instanceof String text && text.length() == 1 ? text.charAt(0) : null;
  }

  /**
   * Returns {@code value} as a long if it is a whole number strictly inside the long range, else
   * null; a double at either bound may be a rounded larger value.
   */
  private static @Nullable Long wholeLong(double value) {
    return value % 1.0 == 0.0 && value > Long.MIN_VALUE && value < Long.MAX_VALUE
        ? (long) value
        : null;
  }

  /** The current token as a Long, BigInteger, Double, String or Boolean, else null. */
  static @Nullable Object scalarValue(JsonParser p) throws IOException {
    return switch (p.currentToken()) {
      case VALUE_NUMBER_INT ->
          p.getNumberType() == JsonParser.NumberType.BIG_INTEGER
              ? p.getBigIntegerValue()
              : p.getLongValue();
      case VALUE_NUMBER_FLOAT -> p.getDoubleValue();
      case VALUE_STRING -> p.getText();
      case VALUE_TRUE -> true;
      case VALUE_FALSE -> false;
      default -> null;
    };
  }

  /**
   * Reads a {@code type} with a reader. A primitive defaults when absent, and takes null as its
   * default only where FAIL_ON_NULL_FOR_PRIMITIVES is off, as in genai's mapper.
   */
  private static final class ScalarDeserializer<T> extends StdScalarDeserializer<T> {
    private final T defaultValue;
    private final Function<@Nullable Object, @Nullable T> reader;

    ScalarDeserializer(
        Class<T> type, T defaultValue, Function<@Nullable Object, @Nullable T> reader) {
      super(type);
      this.defaultValue = defaultValue;
      this.reader = reader;
    }

    @Override
    @SuppressWarnings("unchecked") // handleUnexpectedToken returns a T or throws.
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      T value = reader.apply(scalarValue(p));
      return value != null ? value : (T) ctxt.handleUnexpectedToken(handledType(), p);
    }

    @Override
    public @Nullable T getNullValue(DeserializationContext ctxt) throws JsonMappingException {
      // Genai's mapper leaves FAIL_ON_NULL_FOR_PRIMITIVES off and reads null as the default.
      if (handledType().isPrimitive()) {
        return ctxt.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            ? ctxt.reportInputMismatch(this, "null for a primitive")
            : defaultValue;
      }
      return null;
    }

    @Override
    public @Nullable Object getAbsentValue(DeserializationContext ctxt) {
      return handledType().isPrimitive() ? defaultValue : null;
    }

    // Nulls.AS_EMPTY reads zero or false, as Jackson's own number and boolean readers do.
    @Override
    public T getEmptyValue(DeserializationContext ctxt) {
      return defaultValue;
    }
  }

  /** Reads a map key, which JSON writes as text, as a value of its type is read. */
  private static final class ScalarKeyDeserializer extends KeyDeserializer {
    private final Class<?> type;
    private final Function<@Nullable Object, ?> reader;

    ScalarKeyDeserializer(Class<?> type, Function<@Nullable Object, ?> reader) {
      this.type = type;
      this.reader = reader;
    }

    @Override
    public Object deserializeKey(String key, DeserializationContext ctxt) throws IOException {
      Object value = reader.apply(key);
      return value != null ? value : ctxt.handleWeirdKey(type, key, "does not fit the key type");
    }
  }

  /**
   * Reads a primitive array with Jackson's array reader, which honors the field's content-null
   * setting and {@code ACCEPT_SINGLE_VALUE_AS_ARRAY}, each element read as its type is above.
   */
  private static final class ArrayDeserializer<A> extends StdDeserializer<A>
      implements ContextualDeserializer {
    private final Class<A> type;
    private final @Nullable JsonDeserializer<?> boxed;

    ArrayDeserializer(Class<A> type) {
      this(type, null);
    }

    private ArrayDeserializer(Class<A> type, @Nullable JsonDeserializer<?> boxed) {
      super(type);
      this.type = type;
      this.boxed = boxed;
    }

    @Override
    @SuppressWarnings("unchecked") // A value reader reads Objects.
    public JsonDeserializer<?> createContextual(
        DeserializationContext ctxt, @Nullable BeanProperty property) throws JsonMappingException {
      Class<?> element = type.getComponentType();
      // The primitive's own reader, so a null element reads as its default or fails.
      ObjectArrayDeserializer boxed =
          new ObjectArrayDeserializer(
              ctxt.getTypeFactory().constructArrayType(Primitives.wrap(element)),
              (JsonDeserializer<Object>)
                  ctxt.findRootValueDeserializer(ctxt.constructType(element)),
              null);
      return new ArrayDeserializer<>(type, boxed.createContextual(ctxt, property));
    }

    @Override
    public A deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      Object[] values =
          (Object[]) requireNonNull(boxed, "set by createContextual").deserialize(p, ctxt);
      Object array = Array.newInstance(type.getComponentType(), values.length);
      for (int i = 0; i < values.length; i++) {
        if (values[i] == null) {
          return ctxt.reportInputMismatch(this, "null in a primitive array");
        }
        Array.set(array, i, values[i]);
      }
      return type.cast(array);
    }

    @Override
    public A getEmptyValue(DeserializationContext ctxt) {
      return type.cast(Array.newInstance(type.getComponentType(), 0));
    }
  }

  /**
   * Reads an {@code OptionalInt}, {@code OptionalLong} or {@code OptionalDouble}: null as empty.
   */
  private static final class PrimitiveOptionalDeserializer<T, E> extends StdScalarDeserializer<T> {
    private final Function<@Nullable Object, @Nullable E> reader;
    private final Function<E, T> wrap;
    private final T empty;

    PrimitiveOptionalDeserializer(
        Class<T> type,
        Function<@Nullable Object, @Nullable E> reader,
        Function<E, T> wrap,
        T empty) {
      super(type);
      this.reader = reader;
      this.wrap = wrap;
      this.empty = empty;
    }

    @Override
    @SuppressWarnings("unchecked") // handleUnexpectedToken returns a T or throws.
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      E value = reader.apply(scalarValue(p));
      return value != null ? wrap.apply(value) : (T) ctxt.handleUnexpectedToken(handledType(), p);
    }

    @Override
    public T getNullValue(DeserializationContext ctxt) {
      return empty;
    }
  }
}
