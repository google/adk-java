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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.adk.events.Event;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads a {@link FunctionNode} port's input into its type and writes its output in JSON form, by
 * the port rules {@link FunctionNode} describes.
 */
final class PortCodec<T> {

  /**
   * One mapper for every port: unknown properties are ignored, null fields are written, {@code
   * java.time} values travel as ISO-8601 strings, and genai types as genai writes them. A whole
   * number reads a float only when it has no fraction, while a null primitive, an empty string as a
   * number, boolean, enum or date, a number or boolean as text, and a missing {@link
   * RequiredProperties} property fail.
   */
  static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .withCoercionConfigDefaults(
              config -> config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
          // A Locale or URI writes some values, such as Locale.ROOT, as an empty string.
          .withCoercionConfig(
              LogicalType.OtherScalar,
              config ->
                  config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.TryConvert))
          .withCoercionConfig(
              LogicalType.Textual,
              config ->
                  config
                      .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          .addModule(new Jdk8Module())
          .addModule(new JavaTimeModule())
          .addModule(portModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
          .build();

  /** The port's type, with a primitive boxed. */
  private final JavaType type;

  /** Whether the declared type is primitive, so the port takes no null. */
  private final boolean primitive;

  private PortCodec(JavaType declared) {
    this.primitive = declared.isPrimitive();
    this.type =
        primitive
            ? MAPPER.constructType(
                MethodType.methodType(declared.getRawClass()).wrap().returnType())
            : declared;
  }

  static <T> PortCodec<T> of(Class<T> type) {
    return new PortCodec<>(MAPPER.constructType(type));
  }

  /**
   * Returns {@code value} read as this port's type; {@code what} names the port in errors.
   *
   * @throws NodeInputValidationException if {@code value} does not fit the port
   */
  @SuppressWarnings({"unchecked", "UnusedException"}) // The checks and Jackson guarantee the type.
  @Nullable T decode(@Nullable Object value, String what) {
    Class<?> raw = type.getRawClass();
    if (raw == Void.class) {
      return null;
    }
    if (value == null) {
      if (primitive) {
        throw nullForPrimitive(what);
      }
      return null;
    }
    if (raw == Object.class || hasType(value, type)) {
      return (T) value;
    }
    if (value instanceof Content || value instanceof Event) {
      throw mismatch(what, value.getClass().getSimpleName());
    }
    T converted;
    try {
      converted = MAPPER.convertValue(value, type);
    } catch (RuntimeException e) {
      // Jackson's and genai's messages can quote the value, so no cause.
      throw new NodeInputValidationException(
          "validation error: " + what + " does not match " + type.toCanonical() + ".");
    }
    // A value such as an empty Optional converts to null.
    if (converted == null && primitive) {
      throw nullForPrimitive(what);
    }
    return converted;
  }

  /**
   * Returns {@code value} in JSON form, with the value types a stored session reads back: maps and
   * lists (unmodifiable here), strings, booleans, nulls, {@code Integer}, {@code Long} or {@code
   * BigInteger} for a whole number, and {@code Double} for any other number, a {@code BigDecimal}
   * included.
   *
   * @throws IllegalArgumentException if {@code value} is not of this port's type, has no JSON form,
   *     holds a NaN or infinite number, or writes null
   */
  @SuppressWarnings("UnusedException")
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
    JsonNode tree;
    try {
      tree = MAPPER.valueToTree(value);
    } catch (RuntimeException e) {
      // Jackson's and genai's messages can quote the value, so no cause.
      throw new IllegalArgumentException(what + " has no JSON form as " + type.toCanonical() + ".");
    }
    Object json = jsonNative(tree, what);
    if (json == null) {
      throw new IllegalArgumentException(what + " writes null as " + type.toCanonical() + ".");
    }
    return json;
  }

  /** Returns the port's class, with a primitive boxed. */
  Class<?> rawType() {
    return type.getRawClass();
  }

  private NodeInputValidationException nullForPrimitive(String what) {
    return new NodeInputValidationException(
        "validation error: "
            + what
            + " does not accept null (expected "
            + MethodType.methodType(type.getRawClass()).unwrap().returnType().getName()
            + ").");
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

  /** Returns {@code node} as the values a stored session reads back, collections unmodifiable. */
  private static @Nullable Object jsonNative(JsonNode node, String what) {
    if (node.isObject()) {
      Map<String, @Nullable Object> map = new LinkedHashMap<>();
      node.properties()
          .forEach(field -> map.put(field.getKey(), jsonNative(field.getValue(), what)));
      return Collections.unmodifiableMap(map);
    }
    if (node.isArray()) {
      List<@Nullable Object> list = new ArrayList<>(node.size());
      node.forEach(item -> list.add(jsonNative(item, what)));
      return Collections.unmodifiableList(list);
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToInt()
          ? node.intValue()
          : node.canConvertToLong() ? node.longValue() : node.bigIntegerValue();
    }
    if (node.isNumber()) {
      // Widening a float adds digits it never had.
      double number =
          node.isFloat()
              ? Double.parseDouble(Float.toString(node.floatValue()))
              : node.doubleValue();
      if (!Double.isFinite(number)) {
        throw new IllegalArgumentException(
            what + " holds a NaN or infinite number, which JSON lacks.");
      }
      return number;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNull() || node.isMissingNode()) {
      return null;
    }
    return node.asText();
  }

  private static SimpleModule portModule() {
    SimpleModule module =
        new SimpleModule("PortCodec") {
          @Override
          public void setupModule(SetupContext context) {
            super.setupModule(context);
            // A class-level deserializer takes precedence over the builder genai types declare.
            context.insertAnnotationIntrospector(
                new NopAnnotationIntrospector() {
                  @Override
                  public @Nullable Object findDeserializer(Annotated annotated) {
                    return annotated instanceof AnnotatedClass
                            && annotated.getRawType() != JsonSerializable.class
                            && JsonSerializable.class.isAssignableFrom(annotated.getRawType())
                        ? new GenaiDeserializer(annotated.getRawType())
                        : null;
                  }
                });
          }
        };
    module.addSerializer(JsonSerializable.class, new GenaiSerializer());
    module.setDeserializerModifier(
        new BeanDeserializerModifier() {
          @Override
          public JsonDeserializer<?> modifyDeserializer(
              DeserializationConfig config,
              BeanDescription beanDesc,
              JsonDeserializer<?> deserializer) {
            // Number also reports Integer, but takes any number, fractions included.
            return deserializer.logicalType() == LogicalType.Integer
                    && beanDesc.getBeanClass() != Number.class
                ? new WholeNumberDeserializer(deserializer)
                : deserializer;
          }

          @Override
          public BeanDeserializerBuilder updateBuilder(
              DeserializationConfig config,
              BeanDescription beanDesc,
              BeanDeserializerBuilder builder) {
            builder.setValueInstantiator(
                RequiredProperties.enforce(config, beanDesc, builder.getValueInstantiator()));
            return builder;
          }
        });
    return module;
  }

  /** Writes a genai value as genai's own mapper does, as a session stores it. */
  private static final class GenaiSerializer extends StdSerializer<JsonSerializable> {
    GenaiSerializer() {
      super(JsonSerializable.class);
    }

    @Override
    public void serialize(JsonSerializable value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      JsonSerializable.toJsonNode(value).serialize(gen, provider);
    }
  }

  /** Reads a genai value with genai's own mapper, the reader of the form it writes. */
  private static final class GenaiDeserializer extends StdDeserializer<Object> {
    GenaiDeserializer(Class<?> type) {
      super(type);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      return JsonSerializable.objectMapper().treeToValue(ctxt.readTree(p), handledType());
    }
  }

  /** Reads a float with no fraction, such as 2.0, into a whole-number type, and rejects 2.5. */
  private static final class WholeNumberDeserializer extends DelegatingDeserializer {
    WholeNumberDeserializer(JsonDeserializer<?> delegate) {
      super(delegate);
    }

    @Override
    protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
      return new WholeNumberDeserializer(newDelegatee);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (!p.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
        return super.deserialize(p, ctxt);
      }
      BigDecimal number = p.getDecimalValue();
      if (number.stripTrailingZeros().scale() > 0) {
        return ctxt.handleWeirdNumberValue(handledType(), number, "not a whole number");
      }
      return ctxt.readTreeAsValue(BigIntegerNode.valueOf(number.toBigInteger()), handledType());
    }
  }
}
