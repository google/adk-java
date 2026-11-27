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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.cfg.MutableCoercionConfig;
import com.fasterxml.jackson.databind.deser.AbstractDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.impl.UnsupportedTypeDeserializer;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.deser.std.EnumDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdKeyDeserializer;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.ser.impl.UnknownSerializer;
import com.fasterxml.jackson.databind.ser.impl.UnsupportedTypeSerializer;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.ser.std.StdKeySerializers;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.adk.events.Event;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Primitives;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import com.google.genai.types.Schema;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
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
   * The port mapper: unknown properties are ignored, null fields are written, {@code java.time}
   * values travel as ISO-8601 strings, and genai types as genai writes them. Numbers, booleans and
   * chars, also inside genai types, read as {@link PortScalars} describes, and any other
   * whole-number type reads a float only when it has no fraction. It rejects a null primitive, an
   * empty string as a number, boolean, enum or date, a number as an enum not written as one, a
   * number or boolean as text, and a missing {@link RequiredProperties} property.
   */
  static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .withCoercionConfigDefaults(
              config -> config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
          // A Locale or URI writes some values, such as Locale.ROOT, as an empty string.
          .withCoercionConfig(
              LogicalType.OtherScalar,
              config ->
                  config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.TryConvert))
          .withCoercionConfig(LogicalType.Textual, PortCodec::rejectScalarsAsText)
          .addModule(new Jdk8Module())
          .addModule(new JavaTimeModule())
          .addModule(PortScalars.module())
          .addModule(portModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
          .build();

  /** Genai's own reader of the form genai writes, with the port rules for scalars. */
  private static final ObjectReader GENAI_READER = genaiReader();

  /** {@link #GENAI_READER} rejecting unknown properties. */
  private static final ObjectReader STRICT_GENAI_READER =
      GENAI_READER.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /** {@link #MAPPER} rejecting unknown properties, for a port that {@link #rejectsUnknownKeys}. */
  private static final ObjectMapper STRICT_MAPPER =
      MAPPER.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /** The port's type, with a primitive boxed. */
  private final JavaType type;

  private final Supplier<@Nullable Schema> schema;

  /** Whether the declared type is primitive, so the port takes no null. */
  private final boolean primitive;

  /** {@link #STRICT_MAPPER} if this port {@link #rejectsUnknownKeys}, else {@link #MAPPER}. */
  private final ObjectMapper mapper;

  private PortCodec(JavaType declared, boolean output) {
    this.primitive = declared.isPrimitive();
    this.type =
        primitive
            ? MAPPER.constructType(
                MethodType.methodType(declared.getRawClass()).wrap().returnType())
            : declared;
    // An event output passes through as it is, so no JSON form describes it.
    this.schema =
        output && type.getRawClass() == Event.class
            ? () -> null
            : Suppliers.memoize(() -> PortSchemas.infer(type, output));
    this.mapper = rejectsUnknownKeys() ? STRICT_MAPPER : MAPPER;
  }

  static JavaType javaType(Class<?> type) {
    return MAPPER.constructType(type);
  }

  /**
   * Returns the type a {@code TypeReference} captured.
   *
   * @throws WorkflowConfigurationException if the type holds a type variable, which a {@code
   *     TypeReference} cannot capture, or a wildcard
   */
  static JavaType javaType(TypeReference<?> type) {
    requireFullyKnown(type.getType(), type.getType());
    return MAPPER.getTypeFactory().constructType(type);
  }

  private static void requireFullyKnown(Type part, Type whole) {
    if (part instanceof TypeVariable<?>) {
      throw new WorkflowConfigurationException(
          "The port type "
              + whole.getTypeName()
              + " holds "
              + part.getTypeName()
              + ", which a TypeReference cannot capture. Create the TypeReference where "
              + part.getTypeName()
              + " is known, or use Object.class.");
    }
    if (part instanceof WildcardType) {
      throw new WorkflowConfigurationException(
          "The port type "
              + whole.getTypeName()
              + " holds the wildcard "
              + part.getTypeName()
              + ". Declare the port without it, for example with its bound.");
    }
    if (part instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        requireFullyKnown(argument, whole);
      }
    } else if (part instanceof GenericArrayType array) {
      requireFullyKnown(array.getGenericComponentType(), whole);
    }
  }

  /**
   * Returns the codec of an input port, which Jackson must both read and write.
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write {@code type}, or if it
   *     has no single concrete JSON shape or is an abstract type without a Jackson creator
   */
  static <T> PortCodec<T> input(JavaType type, String what) {
    if (!passesAsIs(type)) {
      requireUsable(type, what, /* read= */ true);
    }
    return new PortCodec<>(type, /* output= */ false);
  }

  /**
   * Returns the codec of an output port, which Jackson must write.
   *
   * @throws WorkflowConfigurationException if Jackson cannot write {@code type}, or if it has no
   *     single concrete JSON shape or is an abstract type without a Jackson creator
   */
  static <T> PortCodec<T> output(JavaType type, String what) {
    if (!passesAsIs(type)) {
      requireUsable(type, what, /* read= */ false);
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

  /**
   * Walks {@code type} down to its elements, checking that each has one concrete JSON shape that
   * Jackson writes and, when {@code read}, also reads, and is not abstract without a creator.
   */
  private static void requireUsable(JavaType type, String what, boolean read) {
    if (type.getRawClass() == Object.class) {
      return;
    }
    if (type.isReferenceType()) {
      throw optionalPort(type, what);
    }
    if (type.isContainerType()) {
      if (type.getKeyType() != null) {
        if (read) {
          requireKeyReadable(type.getKeyType(), "a key of " + what);
        }
        requireKeyReadsBack(type.getKeyType(), "a key of " + what);
      }
      requireUsable(type.getContentType(), "an element of " + what, read);
    } else {
      requireConcrete(type, what);
    }
    if (read) {
      requireReadable(type, what);
    }
    if (!type.isContainerType()) {
      requireWritable(type, what);
    }
  }

  private static void requireReadable(JavaType type, String what) {
    JsonDeserializer<?> deserializer = null;
    String reason = "";
    try {
      deserializer = deserializationContext(mapperFor(type)).findRootValueDeserializer(type);
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
        && writesObject(type)
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
    JsonSerializer<?> serializer = null;
    String reason = "";
    try {
      serializer = mapperFor(type).getSerializerProviderInstance().findValueSerializer(type);
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
   * Throws if {@code type} is polymorphic, or an interface or abstract class without a creator
   * Jackson can call, such as {@code ZoneId}.
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
      deserializer = deserializationContext(MAPPER).findKeyDeserializer(type, null);
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

  /**
   * Throws unless Jackson reads back the map key it writes: without a key serializer, an enum or a
   * {@code @JsonValue} accessor, a key is written with {@code toString()}, which only a String, a
   * number or a type with its own Jackson key parser reads back.
   */
  private static void requireKeyReadsBack(JavaType type, String what) {
    if (writesToStringKey(type) && !readsToStringKeyBack(type)) {
      throw new WorkflowConfigurationException(
          capitalized(what)
              + " has type "
              + type.toCanonical()
              + ", which Jackson writes as a map key with toString(), a form it does not read back."
              + " Use a String, a number, an enum or a type with a @JsonValue accessor, or give the"
              + " type a key serializer and a key deserializer.");
    }
  }

  private static boolean writesToStringKey(JavaType type) {
    try {
      JsonSerializer<?> serializer =
          MAPPER.getSerializerProviderInstance().findKeySerializer(type, null);
      return serializer instanceof StdKeySerializers.Default
          || serializer instanceof StdKeySerializers.Dynamic;
    } catch (JsonMappingException | RuntimeException e) {
      return true;
    }
  }

  private static boolean readsToStringKeyBack(JavaType type) {
    Class<?> raw = type.getRawClass();
    // Older Jackson reads BigDecimal and BigInteger keys through their String constructors.
    if (StdKeyDeserializer.forType(raw) != null
        || raw == BigDecimal.class
        || raw == BigInteger.class) {
      return true;
    }
    try {
      KeyDeserializer deserializer = deserializationContext(MAPPER).findKeyDeserializer(type, null);
      // Jackson's String-creator fallback reads back a toString() that may not round-trip.
      return deserializer != null && !(deserializer instanceof StdKeyDeserializer);
    } catch (JsonMappingException | RuntimeException e) {
      return false;
    }
  }

  static @Nullable TypeSerializer findTypeSerializer(JavaType type) {
    try {
      return MAPPER.getSerializerProviderInstance().findTypeSerializer(type);
    } catch (JsonMappingException | RuntimeException e) {
      return null;
    }
  }

  private static boolean writesObject(JavaType type) {
    try {
      return mapperFor(type).getSerializerProviderInstance().findValueSerializer(type)
          instanceof BeanSerializerBase;
    } catch (JsonMappingException | RuntimeException e) {
      return false;
    }
  }

  /** Whether {@code instantiator} builds a bean from a JSON object, the form a port writes. */
  private static boolean createsFromObject(ValueInstantiator instantiator) {
    return instantiator.canCreateUsingDefault()
        || instantiator.canCreateFromObjectWith()
        || instantiator.canCreateUsingDelegate();
  }

  /**
   * Whether the mapper that reads {@code type} for a port creates it as a bean through a creator,
   * such as an AutoValue factory.
   */
  private static boolean hasBeanCreator(JavaType type) {
    try {
      return deserializationContext(mapperFor(type)).findRootValueDeserializer(type)
              instanceof BeanDeserializerBase bean
          && bean.getValueInstantiator() != null
          && bean.getValueInstantiator().canInstantiate();
    } catch (JsonMappingException | RuntimeException e) {
      return false;
    }
  }

  /** Returns the mapper that handles {@code type}: genai's own for a genai type, as a port does. */
  static ObjectMapper mapperFor(JavaType type) {
    return JsonSerializable.class.isAssignableFrom(type.getRawClass())
        ? JsonSerializable.objectMapper()
        : MAPPER;
  }

  static DefaultDeserializationContext deserializationContext(ObjectMapper mapper) {
    return ((DefaultDeserializationContext) mapper.getDeserializationContext())
        .createDummyInstance(mapper.getDeserializationConfig());
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

  /**
   * Whether this port rejects unknown keys at any depth: it reads an {@code Event} or genai value,
   * or a list, map or array of them.
   */
  boolean rejectsUnknownKeys() {
    JavaType value = type;
    while (value.isContainerType()) {
      value = value.getContentType();
    }
    return Event.class.isAssignableFrom(value.getRawClass())
        || JsonSerializable.class.isAssignableFrom(value.getRawClass());
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
    if (raw == Object.class || hasType(value, type, /* trustGenericFields= */ false)) {
      return (T) value;
    }
    if (value instanceof Event) {
      throw mismatch(what, value.getClass().getSimpleName());
    }
    boolean fromMessage = value instanceof Content;
    if (value instanceof Content content) {
      value = MessageReader.readValue(content, schema(), what);
    }
    T converted;
    try {
      converted = mapper.convertValue(value, type);
    } catch (RuntimeException e) {
      // Jackson's and genai's messages can quote the value, so no cause.
      throw doesNotMatch(what);
    }
    // A message's JSON null is not a value.
    if (converted == null && fromMessage) {
      throw doesNotMatch(what);
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
  @Nullable Object encode(@Nullable Object value, String what) {
    Object json = encodeNullable(value, what);
    if (json == null && value != null) {
      throw new IllegalArgumentException(what + " writes null as " + type.toCanonical() + ".");
    }
    return json;
  }

  /**
   * Like {@link #encode}, but returns null for a value that writes null, such as an empty {@code
   * OptionalInt}.
   */
  @SuppressWarnings("UnusedException")
  @Nullable Object encodeNullable(@Nullable Object value, String what) {
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
    // Converts elements of another type, which an unchecked cast lets through. An output port need
    // not be readable, so a generic type's own fields are trusted.
    if (!hasType(value, type, /* trustGenericFields= */ true)) {
      try {
        value = mapper.convertValue(value, type);
      } catch (RuntimeException e) {
        // Jackson's message can quote the value, so no cause.
        throw new IllegalArgumentException(what + " does not match " + type.toCanonical() + ".");
      }
    }
    JsonNode tree;
    try {
      tree = MAPPER.valueToTree(value);
    } catch (RuntimeException e) {
      // Jackson's and genai's messages can quote the value, so no cause.
      throw new IllegalArgumentException(what + " has no JSON form as " + type.toCanonical() + ".");
    }
    return jsonNative(tree, what);
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

  private NodeInputValidationException doesNotMatch(String what) {
    return new NodeInputValidationException(
        "validation error: " + what + " does not match " + type.toCanonical() + ".");
  }

  /**
   * Whether {@code value} already has {@code type}, down to each element and map entry. A generic
   * type's fields count as matching only when {@code trustGenericFields}.
   */
  private static boolean hasType(Object value, JavaType type, boolean trustGenericFields) {
    if (!type.getRawClass().isInstance(value)) {
      return false;
    }
    if (type.isCollectionLikeType()) {
      return ((Collection<?>) value)
          .stream()
              .allMatch(
                  item -> item == null || hasType(item, type.getContentType(), trustGenericFields));
    }
    if (type.isMapLikeType()) {
      return ((Map<?, ?>) value)
          .entrySet().stream()
              .allMatch(
                  entry ->
                      (entry.getKey() == null
                              || hasType(entry.getKey(), type.getKeyType(), trustGenericFields))
                          && (entry.getValue() == null
                              || hasType(
                                  entry.getValue(), type.getContentType(), trustGenericFields)));
    }
    if (type.isArrayType()) {
      return type.getContentType().isPrimitive()
          || Arrays.stream((Object[]) value)
              .allMatch(
                  item -> item == null || hasType(item, type.getContentType(), trustGenericFields));
    }
    // A generic type's fields can hold anything an unchecked cast let in.
    return !type.isContainerType() && (trustGenericFields || !type.hasGenericTypes());
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
            if (Primitives.wrap(beanDesc.getBeanClass()) == Boolean.class) {
              return new IndexedValueDeserializer(
                  deserializer, ImmutableList.of(false, true), /* readsIndex= */ false);
            }
            // Number also reports Integer, but takes any number, fractions included.
            return deserializer.logicalType() == LogicalType.Integer
                    && beanDesc.getBeanClass() != Number.class
                ? new WholeNumberDeserializer(deserializer)
                : deserializer;
          }

          @Override
          public JsonDeserializer<?> modifyEnumDeserializer(
              DeserializationConfig config,
              JavaType type,
              BeanDescription beanDesc,
              JsonDeserializer<?> deserializer) {
            // A JsonCreator enum keeps its own reader.
            return deserializer instanceof EnumDeserializer
                ? new IndexedValueDeserializer(
                    deserializer,
                    ImmutableList.copyOf(type.getRawClass().getEnumConstants()),
                    /* readsIndex= */ false)
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

  private static ObjectReader genaiReader() {
    ObjectMapper mapper =
        JsonSerializable.objectMapper().copy().registerModule(PortScalars.module());
    rejectScalarsAsText(mapper.coercionConfigFor(LogicalType.Textual));
    return mapper.reader();
  }

  private static void rejectScalarsAsText(MutableCoercionConfig config) {
    config
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
  }

  /**
   * Reads a genai value with {@link #GENAI_READER}. Genai's reader drops unknown keys, so this
   * rejects them when the calling mapper does.
   */
  private static final class GenaiDeserializer extends StdDeserializer<Object> {
    GenaiDeserializer(Class<?> type) {
      super(type);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      ObjectReader reader =
          ctxt.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              ? STRICT_GENAI_READER
              : GENAI_READER;
      return reader.treeToValue(ctxt.readTree(p), handledType());
    }
  }

  /** Reads a float with no fraction, such as 2.0, into a whole-number type, and rejects 2.5. */
  private static final class WholeNumberDeserializer extends DelegatingDeserializer {
    private static final int MAX_WHOLE_NUMBER_DIGITS = 1000;

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
      BigDecimal whole = number.stripTrailingZeros();
      if (whole.scale() > 0) {
        return ctxt.handleWeirdNumberValue(handledType(), number, "not a whole number");
      }
      // Caps the integer digits before converting untrusted input, which lint requires.
      // A long, since the subtraction overflows an int for an exponent such as 1e2147483647.
      if ((long) whole.precision() - whole.scale() > MAX_WHOLE_NUMBER_DIGITS) {
        return ctxt.handleWeirdNumberValue(handledType(), number, "too many digits");
      }
      return ctxt.readTreeAsValue(BigIntegerNode.valueOf(whole.toBigIntegerExact()), handledType());
    }
  }

  /**
   * Reads like its delegate but rejects text padded with whitespace. Where Jackson writes each of
   * {@code values} as its own index, it also reads an index n as {@code values.get(n)}. Jackson
   * reads only the instance {@link #createContextual} returns, which decides {@code readsIndex}.
   */
  private static final class IndexedValueDeserializer extends DelegatingDeserializer {
    private final ImmutableList<?> values;
    private final boolean readsIndex;

    IndexedValueDeserializer(
        JsonDeserializer<?> delegate, ImmutableList<?> values, boolean readsIndex) {
      super(delegate);
      this.values = values;
      this.readsIndex = readsIndex;
    }

    @Override
    protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
      return new IndexedValueDeserializer(newDelegatee, values, readsIndex);
    }

    @Override
    public JsonDeserializer<?> createContextual(
        DeserializationContext ctxt, @Nullable BeanProperty property) throws JsonMappingException {
      JsonDeserializer<?> delegate =
          ctxt.handleSecondaryContextualization(
              _delegatee, property, ctxt.constructType(handledType()));
      return new IndexedValueDeserializer(delegate, values, writesIndexes(property));
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (readsIndex
          && PortScalars.scalarValue(p) instanceof Long n
          && n >= 0
          && n < values.size()) {
        return values.get(n.intValue());
      }
      String text = p.hasToken(JsonToken.VALUE_STRING) ? p.getText() : null;
      Object value = super.deserialize(p, ctxt);
      // Jackson's enum reader trims a name it does not find; keep only names the type writes.
      if (text != null
          && value != null
          && !text.equals(text.trim())
          && !text.equals(MAPPER.valueToTree(value).asText())) {
        return ctxt.handleWeirdStringValue(handledType(), text, "not a name it writes");
      }
      return value;
    }

    /**
     * Whether Jackson writes each of {@code values} as its own index in {@code property}, or as a
     * top-level value when {@code property} is null. A field with its own {@code JsonSerialize}, or
     * a serializer that fails here, counts as not writing them.
     */
    private boolean writesIndexes(@Nullable BeanProperty property) {
      if (property != null && property.getAnnotation(JsonSerialize.class) != null) {
        return false;
      }
      SerializerProvider provider = MAPPER.getSerializerProviderInstance();
      try {
        JsonSerializer<Object> serializer = provider.findValueSerializer(handledType(), property);
        for (int i = 0; i < values.size(); i++) {
          try (TokenBuffer tokens = new TokenBuffer(MAPPER, /* hasNativeIds= */ false)) {
            serializer.serialize(values.get(i), tokens, provider);
            JsonParser written = tokens.asParserOnFirstToken();
            if (!written.hasToken(JsonToken.VALUE_NUMBER_INT) || written.getLongValue() != i) {
              return false;
            }
          }
        }
        return true;
      } catch (IOException | RuntimeException e) {
        return false;
      }
    }
  }
}
