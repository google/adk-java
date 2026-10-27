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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.AbstractDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.impl.UnsupportedTypeDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.impl.UnknownSerializer;
import com.fasterxml.jackson.databind.ser.impl.UnsupportedTypeSerializer;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.adk.events.Event;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import com.google.genai.types.Schema;
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
  static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .addModule(new Jdk8Module())
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
          .build();

  private final JavaType type;
  private final Supplier<@Nullable Schema> schema;

  private PortCodec(JavaType type, boolean output) {
    this.type = type;
    // An event output passes through as it is, so no JSON form describes it.
    this.schema =
        output && type.getRawClass() == Event.class
            ? () -> null
            : Suppliers.memoize(() -> PortSchemas.infer(type));
  }

  static JavaType javaType(Class<?> type) {
    return MAPPER.getTypeFactory().constructType(MethodType.methodType(type).wrap().returnType());
  }

  static JavaType javaType(TypeReference<?> type) {
    return MAPPER.getTypeFactory().constructType(type);
  }

  /**
   * Returns the codec of an input port, which Jackson must both read and write.
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write {@code type}, or if it
   *     has no single concrete JSON shape
   */
  static <T> PortCodec<T> input(JavaType type, String what) {
    if (!passesAsIs(type)) {
      requireReadable(type, what);
      requireWritable(type, what);
    }
    return new PortCodec<>(type, /* output= */ false);
  }

  /**
   * Returns the codec of an output port, which Jackson must write.
   *
   * @throws WorkflowConfigurationException if Jackson cannot write {@code type}, or if it has no
   *     single concrete JSON shape
   */
  static <T> PortCodec<T> output(JavaType type, String what) {
    if (!passesAsIs(type)) {
      requireWritable(type, what);
    }
    return new PortCodec<>(type, /* output= */ true);
  }

  /** Whether a port of {@code type} handles values without a Jackson check. */
  private static boolean passesAsIs(JavaType type) {
    Class<?> raw = type.getRawClass();
    return raw == Object.class || raw == Void.class || raw == Content.class || raw == Event.class;
  }

  /** A port has no "absent" value: a missing input arrives as null, and null sends no output. */
  private static WorkflowConfigurationException optionalPort(JavaType type, String what) {
    return new WorkflowConfigurationException(
        capitalized(what)
            + " has type "
            + type.toCanonical()
            + ". Declare it without Optional: a missing input arrives as null, and a null output"
            + " sends none.");
  }

  private static void requireReadable(JavaType type, String what) {
    if (type.getRawClass() == Object.class) {
      return;
    }
    if (type.isReferenceType()) {
      throw optionalPort(type, what);
    }
    if (type.isContainerType()) {
      if (type.getKeyType() != null) {
        requireKeyReadable(type.getKeyType(), "a key of " + what);
      }
      requireReadable(type.getContentType(), "an element of " + what);
    } else {
      requireConcrete(type, what);
    }
    JsonDeserializer<?> deserializer = null;
    String reason = "";
    try {
      deserializer = deserializationContext().findRootValueDeserializer(type);
    } catch (JsonMappingException | RuntimeException e) {
      reason = reason(e);
    }
    boolean readable =
        deserializer != null
            && !(deserializer instanceof AbstractDeserializer)
            && !(deserializer instanceof UnsupportedTypeDeserializer)
            && !(deserializer instanceof ValueInstantiator.Gettable gettable
                && gettable.getValueInstantiator() != null
                && !gettable.getValueInstantiator().canInstantiate());
    if (!readable) {
      throw new WorkflowConfigurationException(
          capitalized(what)
              + " has type "
              + type.toCanonical()
              + ", which Jackson cannot create from JSON"
              + reason
              + ". Use a record, a class with a constructor Jackson can call, or Object.class.");
    }
    // A @JsonValue type writes a single value, so only an object-writing type needs this.
    if (deserializer instanceof BeanDeserializerBase bean
        && bean.getValueInstantiator() != null
        && beanSerializer(type) != null
        && !createsFromObject(bean.getValueInstantiator())) {
      throw new WorkflowConfigurationException(
          capitalized(what)
              + " has type "
              + type.toCanonical()
              + ", which Jackson can create only from a string or number, not from the JSON object"
              + " it writes. Use a record, a class with a no-argument constructor, or a"
              + " @JsonCreator constructor with @JsonProperty parameters.");
    }
  }

  private static void requireWritable(JavaType type, String what) {
    if (type.getRawClass() == Object.class) {
      return;
    }
    if (type.isReferenceType()) {
      throw optionalPort(type, what);
    }
    if (type.isContainerType()) {
      requireWritable(type.getContentType(), "an element of " + what);
      return;
    }
    requireConcrete(type, what);
    JsonSerializer<?> serializer = null;
    String reason = "";
    try {
      serializer = MAPPER.getSerializerProviderInstance().findValueSerializer(type);
    } catch (JsonMappingException | RuntimeException e) {
      reason = reason(e);
    }
    // An UnknownSerializer stands for a class with no properties, which fails when written.
    if (serializer == null
        || serializer instanceof UnsupportedTypeSerializer
        || serializer instanceof UnknownSerializer) {
      throw new WorkflowConfigurationException(
          capitalized(what)
              + " has type "
              + type.toCanonical()
              + ", which Jackson cannot write as JSON"
              + reason
              + ". Use a record, a class with getters, or Object.class.");
    }
  }

  /**
   * Throws unless {@code type} has one concrete JSON shape: a polymorphic type is rejected, and so
   * is an interface or abstract class without a creator Jackson can call.
   */
  private static void requireConcrete(JavaType type, String what) {
    String problem;
    if (findTypeSerializer(type) != null) {
      problem = "is polymorphic (@JsonTypeInfo)";
    } else if (type.isAbstract()
        && !type.isEnumType()
        && !type.isPrimitive()
        && !hasBeanCreator(type)) {
      problem = "is an interface or abstract class without a Jackson creator";
    } else {
      return;
    }
    String fix;
    if (Number.class.isAssignableFrom(type.getRawClass())) {
      fix = "Declare the port as Long, Double or BigDecimal, or use Object.class.";
    } else if (CharSequence.class.isAssignableFrom(type.getRawClass())) {
      fix = "Declare the port as String.";
    } else {
      fix =
          "Declare the port as a concrete record, wrap the hierarchy in one, or use Object.class.";
    }
    throw new WorkflowConfigurationException(
        capitalized(what) + " has type " + type.toCanonical() + ", which " + problem + ". " + fix);
  }

  private static void requireKeyReadable(JavaType type, String what) {
    KeyDeserializer deserializer = null;
    String reason = "";
    try {
      deserializer = deserializationContext().findKeyDeserializer(type, null);
    } catch (JsonMappingException | RuntimeException e) {
      reason = reason(e);
    }
    if (deserializer == null) {
      throw new WorkflowConfigurationException(
          capitalized(what)
              + " has type "
              + type.toCanonical()
              + ", which Jackson cannot read as a map key"
              + reason
              + ". Use a String, a number, an enum, or Object.class.");
    }
  }

  static @Nullable TypeSerializer findTypeSerializer(JavaType type) {
    try {
      return MAPPER.getSerializerProviderInstance().findTypeSerializer(type);
    } catch (JsonMappingException | RuntimeException e) {
      return null;
    }
  }

  /** Returns the serializer that writes {@code type} as a bean, or {@code null} if none does. */
  static @Nullable BeanSerializerBase beanSerializer(JavaType type) {
    try {
      return MAPPER.getSerializerProviderInstance().findValueSerializer(type)
              instanceof BeanSerializerBase bean
          ? bean
          : null;
    } catch (JsonMappingException | RuntimeException e) {
      return null;
    }
  }

  /** Whether {@code instantiator} builds a bean from a JSON object, the form a port writes. */
  private static boolean createsFromObject(ValueInstantiator instantiator) {
    return instantiator.canCreateUsingDefault()
        || instantiator.canCreateFromObjectWith()
        || instantiator.canCreateUsingDelegate();
  }

  /**
   * Whether Jackson creates {@code type} as a bean through a creator, such as an AutoValue factory.
   */
  private static boolean hasBeanCreator(JavaType type) {
    try {
      return deserializationContext().findRootValueDeserializer(type)
              instanceof BeanDeserializerBase bean
          && bean.getValueInstantiator() != null
          && bean.getValueInstantiator().canInstantiate();
    } catch (JsonMappingException | RuntimeException e) {
      return false;
    }
  }

  private static DefaultDeserializationContext deserializationContext() {
    return ((DefaultDeserializationContext) MAPPER.getDeserializationContext())
        .createDummyInstance(MAPPER.getDeserializationConfig());
  }

  /** The first line of Jackson's message about a type, which names the type and never a value. */
  private static String reason(Exception e) {
    String message =
        e instanceof JsonMappingException mapping ? mapping.getOriginalMessage() : e.getMessage();
    return message == null ? "" : " (" + message.lines().findFirst().orElse("") + ")";
  }

  private static String capitalized(String what) {
    return Character.toUpperCase(what.charAt(0)) + what.substring(1);
  }

  /** Returns the schema inferred for this port's JSON form, or {@code null} if it has none. */
  @Nullable Schema schema() {
    return schema.get();
  }

  /** Whether this port reads a {@code Content}. */
  boolean takesContent() {
    return type.getRawClass() == Content.class;
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
