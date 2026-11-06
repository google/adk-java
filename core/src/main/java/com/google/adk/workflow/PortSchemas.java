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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparingInt;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.CreatorProperty;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Infers the schema of a {@link FunctionNode} port's JSON form from the port's type. An input
 * port's schema lists the keys its Jackson mapper reads, and an output port's the keys it writes.
 */
final class PortSchemas {

  // Jackson writes a char[] as a string, like a byte[] in base64.
  private static final ImmutableSet<Class<?>> STRINGS =
      ImmutableSet.of(
          String.class, CharSequence.class, Character.class, byte[].class, char[].class);
  private static final ImmutableSet<Class<?>> INTEGERS =
      ImmutableSet.of(Integer.class, Long.class, Short.class, Byte.class, BigInteger.class);
  private static final ImmutableSet<Class<?>> NUMBERS =
      ImmutableSet.of(Float.class, Double.class, BigDecimal.class, Number.class);
  private static final SerializationConfig CONFIG = PortCodec.MAPPER.getSerializationConfig();
  private static final AnnotationIntrospector ANNOTATIONS = CONFIG.getAnnotationIntrospector();

  /** Whether this describes what an output port writes rather than what an input port reads. */
  private final boolean output;

  /** The records and POJOs being described, to find a type that contains itself. */
  private final Set<JavaType> enclosing = new HashSet<>();

  private boolean recursive;

  private PortSchemas(boolean output) {
    this.output = output;
  }

  /**
   * Returns the schema of {@code type}'s JSON form as an output port writes it or an input port
   * reads it, or {@code null} if that form has no fixed shape or {@code type} contains itself.
   */
  static @Nullable Schema infer(JavaType type, boolean output) {
    PortSchemas inference = new PortSchemas(output);
    Schema schema = inference.describe(type);
    return inference.recursive ? null : schema;
  }

  private @Nullable Schema describe(JavaType type) {
    // Boxes a primitive, so each set lists only the boxed type.
    Class<?> raw = MethodType.methodType(type.getRawClass()).wrap().returnType();
    if (STRINGS.contains(raw)) {
      return typed(Type.Known.STRING);
    }
    if (raw == Boolean.class) {
      return typed(Type.Known.BOOLEAN);
    }
    if (INTEGERS.contains(raw)) {
      return typed(Type.Known.INTEGER);
    }
    if (NUMBERS.contains(raw)) {
      return typed(Type.Known.NUMBER);
    }
    if (raw.isEnum()) {
      return describeEnum(raw);
    }
    if (type.isReferenceType()) {
      Schema content = describe(type.getContentType());
      return content == null ? null : content.toBuilder().nullable(true).build();
    }
    if (type.isArrayType() || type.isCollectionLikeType()) {
      Schema items = describe(type.getContentType());
      Schema.Builder array = Schema.builder().type(Type.Known.ARRAY);
      return (items == null ? array : array.items(items)).build();
    }
    if (type.isMapLikeType()) {
      return typed(Type.Known.OBJECT);
    }
    return describeObject(type);
  }

  /** Returns a string enum of the values the mapper writes, or {@code null} if one is not text. */
  private static @Nullable Schema describeEnum(Class<?> type) {
    ImmutableList<JsonNode> values;
    try {
      values =
          Arrays.stream(type.getEnumConstants())
              .map(constant -> PortCodec.MAPPER.<JsonNode>valueToTree(constant))
              .collect(toImmutableList());
    } catch (RuntimeException e) {
      return null;
    }
    return values.stream().allMatch(JsonNode::isTextual)
        ? Schema.builder()
            .type(Type.Known.STRING)
            .enum_(values.stream().map(JsonNode::textValue).collect(toImmutableList()))
            .build()
        : null;
  }

  /** Returns the schema of a record or POJO, or {@code null} if its keys are not fixed. */
  private @Nullable Schema describeObject(JavaType type) {
    // A polymorphic type also has its subtypes' properties.
    Bean bean = PortCodec.findTypeSerializer(type) == null ? bean(type) : null;
    if (bean == null || !hasFixedKeys(type)) {
      return null;
    }
    if (!enclosing.add(type)) {
      recursive = true;
      return null;
    }
    BeanDescription description = CONFIG.introspect(type);
    ImmutableMap.Builder<String, Schema> properties = ImmutableMap.builder();
    ImmutableList.Builder<String> required = ImmutableList.builder();
    for (BeanProperty property : bean.properties()) {
      JavaType propertyType = property.getType();
      boolean nullable =
          !propertyType.isPrimitive()
              && (propertyType.isReferenceType()
                  || RequiredProperties.hasNullableAnnotation(description, property));
      Class<?> raw = propertyType.getRawClass();
      boolean emptiable =
          nullable
              || propertyType.isContainerType()
              || raw == Object.class
              || CharSequence.class.isAssignableFrom(raw)
              || Iterable.class.isAssignableFrom(raw)
              || JsonNode.class.isAssignableFrom(raw);
      // The mapper omits a key only when its value can be one the inclusion leaves out.
      JsonInclude.Include include = inclusion(type, property);
      boolean omitted =
          switch (include) {
            case NON_NULL, NON_ABSENT -> nullable;
            case NON_EMPTY -> emptiable;
            case NON_DEFAULT ->
                emptiable || MethodType.methodType(raw).unwrap().returnType().isPrimitive();
            case CUSTOM -> true;
            default -> false;
          };
      if (output && omitted && include != JsonInclude.Include.CUSTOM) {
        // Of the inclusions that omit null, only NON_NULL writes an empty Optional as null.
        nullable = include == JsonInclude.Include.NON_NULL && propertyType.isReferenceType();
      }
      if (hasElementTypeId(property)) {
        propertyType = propertyType.withContentType(TypeFactory.unknownType());
      }
      // Whether an Optional is written as null depends on its inclusion, as computed above.
      if (propertyType.isReferenceType()) {
        propertyType = propertyType.getContentType();
      }
      // A serializer, type id, object id or shape of the property's own may use another type.
      Schema schema = hasOwnForm(property) ? null : describe(propertyType);
      if (schema == null) {
        // A property of no fixed shape takes any value.
        schema = Schema.builder().build();
      } else if (nullable) {
        schema = schema.toBuilder().nullable(true).build();
      }
      properties.put(property.getName(), schema);
      // Any of an aliased property's names may carry it, so none of them is required.
      List<PropertyName> aliases = output ? ImmutableList.of() : property.findAliases(CONFIG);
      for (PropertyName alias : aliases) {
        properties.put(alias.getSimpleName(), schema);
      }
      // An input needs the keys the runtime fails a value without; Jackson enforces required = true
      // only on a creator parameter.
      if (output
          ? !omitted
          : aliases.isEmpty()
              && ((property instanceof CreatorProperty && property.isRequired())
                  || bean.required().contains(property.getName()))) {
        required.add(property.getName());
      }
    }
    enclosing.remove(type);
    ImmutableList<String> requiredNames = required.build();
    Schema.Builder object =
        Schema.builder().type(Type.Known.OBJECT).properties(properties.buildKeepingLast());
    return (requiredNames.isEmpty() ? object : object.required(requiredNames)).build();
  }

  /** The properties a port writes or reads, and those an input port fails a value without. */
  private record Bean(
      ImmutableList<? extends BeanProperty> properties, ImmutableSet<String> required) {}

  /** Returns what the port writes or reads of {@code type}, or {@code null} if it is no bean. */
  private @Nullable Bean bean(JavaType type) {
    ObjectMapper mapper = mapper(type);
    Object handler;
    try {
      handler =
          output
              ? mapper.getSerializerProviderInstance().findValueSerializer(type)
              : ((DefaultDeserializationContext) mapper.getDeserializationContext())
                  .createDummyInstance(mapper.getDeserializationConfig())
                  .findRootValueDeserializer(type);
    } catch (JsonMappingException | RuntimeException e) {
      return null;
    }
    if (handler instanceof BeanSerializerBase serializer) {
      return new Bean(ImmutableList.copyOf(serializer.properties()), ImmutableSet.of());
    }
    BeanDeserializerBase deserializer = handler instanceof BeanDeserializerBase bean ? bean : null;
    ValueInstantiator creator = deserializer == null ? null : deserializer.getValueInstantiator();
    // A delegating creator reads the form of another type.
    if (creator == null
        || !(creator.canCreateUsingDefault() || creator.canCreateFromObjectWith())) {
      return null;
    }
    ImmutableList<String> declared =
        CONFIG.introspect(type).findProperties().stream()
            .map(BeanPropertyDefinition::getName)
            .collect(toImmutableList());
    // Jackson keeps an ignored creator parameter, to pass it null.
    return new Bean(
        Streams.stream(deserializer.properties())
            .filter(property -> !property.isIgnorable())
            .sorted(comparingInt(property -> declared.indexOf(property.getName())))
            .collect(toImmutableList()),
        RequiredProperties.required(creator));
  }

  /** Returns the mapper that handles {@code type}: genai's own for a genai type, as a port does. */
  private static ObjectMapper mapper(JavaType type) {
    return JsonSerializable.class.isAssignableFrom(type.getRawClass())
        ? JsonSerializable.objectMapper()
        : PortCodec.MAPPER;
  }

  /** Returns how the mapper includes a property on write: its own, else its class's or mapper's. */
  private static JsonInclude.Include inclusion(JavaType type, BeanProperty property) {
    SerializationConfig config = mapper(type).getSerializationConfig();
    BeanDescription description = config.introspect(type);
    JsonInclude.Value include =
        description.findPropertyInclusion(config.getDefaultPropertyInclusion(type.getRawClass()));
    JsonInclude.Value own =
        property.getMember() == null
            ? JsonInclude.Value.empty()
            : ANNOTATIONS.findPropertyInclusion(property.getMember());
    JsonInclude.Include value = include.withOverrides(own).getValueInclusion();
    // A class-level NON_DEFAULT compares each value with a default instance, if Jackson can create
    // one, so it may omit any value, as an unknown filter may.
    return value == JsonInclude.Include.NON_DEFAULT
            && include.getValueInclusion() == JsonInclude.Include.NON_DEFAULT
            && description.findDefaultConstructor() != null
        ? JsonInclude.Include.CUSTOM
        : value;
  }

  /**
   * Whether the keys of {@code type} are the names of its properties, and it has no any-setter to
   * take other keys. An any-getter, an unwrapped property, an object id or a shape other than an
   * object uses other keys, or no object.
   */
  private static boolean hasFixedKeys(JavaType type) {
    try {
      BeanDescription description = CONFIG.introspect(type);
      JsonFormat.Value format = description.findExpectedFormat();
      return description.findAnyGetter() == null
          && description.findAnySetterAccessor() == null
          && description.getObjectIdInfo() == null
          && (!format.hasShape() || format.getShape() == JsonFormat.Shape.OBJECT)
          && description.findProperties().stream()
              .flatMap(RequiredProperties::members)
              .noneMatch(member -> ANNOTATIONS.findUnwrappingNameTransformer(member) != null);
    } catch (RuntimeException e) {
      // Jackson rejects a malformed or second any-getter or any-setter.
      return false;
    }
  }

  /**
   * Whether {@code property} has a serializer or deserializer, converter, type id, object id or
   * shape of its own. A type id on a container goes on each element; see {@link #hasElementTypeId}.
   */
  private boolean hasOwnForm(BeanProperty property) {
    if ((property instanceof BeanPropertyWriter writer && writer.getTypeSerializer() != null)
        || (property instanceof SettableBeanProperty settable
            && settable.getValueTypeDeserializer() != null)) {
      return true;
    }
    AnnotatedMember member = property.getMember();
    if (member == null) {
      return false;
    }
    JsonFormat.Value format = ANNOTATIONS.findFormat(member);
    boolean ownCodec =
        output
            ? ANNOTATIONS.findSerializer(member) != null
                || ANNOTATIONS.findContentSerializer(member) != null
                || ANNOTATIONS.findSerializationConverter(member) != null
            : ANNOTATIONS.findDeserializer(member) != null
                || ANNOTATIONS.findContentDeserializer(member) != null
                || ANNOTATIONS.findDeserializationConverter(member) != null;
    return ownCodec
        || ANNOTATIONS.findObjectIdInfo(member) != null
        || (format != null && format.hasShape());
  }

  /** Whether the {@code @JsonTypeInfo} of a container property puts a type id on each element. */
  private static boolean hasElementTypeId(BeanProperty property) {
    JavaType type = property.getType();
    return property.getMember() != null
        && (type.isContainerType() || type.isReferenceType())
        && ANNOTATIONS.findPropertyContentTypeResolver(CONFIG, property.getMember(), type) != null;
  }

  private static Schema typed(Type.Known type) {
    return Schema.builder().type(type).build();
  }
}
