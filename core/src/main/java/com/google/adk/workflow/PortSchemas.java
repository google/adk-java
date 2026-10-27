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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotationMap;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Infers the schema of a {@link FunctionNode} port's JSON form from the port's type. Records, POJOs
 * and enums are read through the port's Jackson mapper, so names and values are the ones written.
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

  /** The records and POJOs being described, to find a type that contains itself. */
  private final Set<JavaType> enclosing = new HashSet<>();

  private boolean recursive;

  private PortSchemas() {}

  /**
   * Returns the schema of {@code type}'s JSON form as a port writes it, or {@code null} if that
   * form has no fixed shape or {@code type} contains itself.
   */
  static @Nullable Schema infer(JavaType type) {
    PortSchemas inference = new PortSchemas();
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
    // A polymorphic type also writes its subtypes' properties.
    BeanSerializerBase serializer =
        PortCodec.findTypeSerializer(type) == null ? PortCodec.beanSerializer(type) : null;
    if (serializer == null) {
      return null;
    }
    ImmutableList<PropertyWriter> writers = ImmutableList.copyOf(serializer.properties());
    if (!hasFixedKeys(type, writers)) {
      return null;
    }
    if (!enclosing.add(type)) {
      recursive = true;
      return null;
    }
    ImmutableMap.Builder<String, Schema> properties = ImmutableMap.builder();
    ImmutableList.Builder<String> required = ImmutableList.builder();
    for (PropertyWriter property : writers) {
      JavaType propertyType = property.getType();
      boolean nullable =
          !propertyType.isPrimitive()
              && (propertyType.isReferenceType() || hasNullableAnnotation(property.getMember()));
      if (hasElementTypeId(property)) {
        propertyType = propertyType.withContentType(TypeFactory.unknownType());
      }
      // A serializer, type id, object id or shape of the property's own may write another type.
      Schema schema = hasOwnForm(property) ? null : describe(propertyType);
      if (schema == null) {
        // A property of no fixed shape takes any value.
        schema = Schema.builder().build();
      } else if (nullable) {
        schema = schema.toBuilder().nullable(true).build();
      }
      properties.put(property.getName(), schema);
      if (!nullable && !isMarkedNotRequired(property)) {
        required.add(property.getName());
      }
    }
    enclosing.remove(type);
    ImmutableList<String> requiredNames = required.build();
    Schema.Builder object =
        Schema.builder().type(Type.Known.OBJECT).properties(properties.buildOrThrow());
    return (requiredNames.isEmpty() ? object : object.required(requiredNames)).build();
  }

  /**
   * Whether the keys {@code type} writes are the names of its {@code writers}, and it has no
   * any-setter to take other keys. An any-getter, an unwrapped property, an object id or a shape
   * other than an object writes other keys, or no object.
   */
  private static boolean hasFixedKeys(JavaType type, ImmutableList<PropertyWriter> writers) {
    try {
      BeanDescription description = CONFIG.introspect(type);
      JsonFormat.Value format = description.findExpectedFormat();
      return description.findAnyGetter() == null
          && description.findAnySetterAccessor() == null
          && description.getObjectIdInfo() == null
          && (!format.hasShape() || format.getShape() == JsonFormat.Shape.OBJECT)
          && writers.stream()
              .noneMatch(writer -> writer instanceof BeanPropertyWriter w && w.isUnwrapping());
    } catch (RuntimeException e) {
      // Jackson rejects a malformed or second any-getter or any-setter.
      return false;
    }
  }

  /**
   * Whether {@code property} has a serializer, content serializer, type id, object id or shape of
   * its own. A type id on a container goes on each element instead; see {@link #hasElementTypeId}.
   */
  private static boolean hasOwnForm(PropertyWriter property) {
    if (property instanceof BeanPropertyWriter writer && writer.getTypeSerializer() != null) {
      return true;
    }
    AnnotatedMember member = property.getMember();
    if (member == null) {
      return false;
    }
    JsonFormat.Value format = ANNOTATIONS.findFormat(member);
    return ANNOTATIONS.findSerializer(member) != null
        || ANNOTATIONS.findContentSerializer(member) != null
        || ANNOTATIONS.findObjectIdInfo(member) != null
        || (format != null && format.hasShape());
  }

  /** Whether the {@code @JsonTypeInfo} of a container property puts a type id on each element. */
  private static boolean hasElementTypeId(PropertyWriter property) {
    JavaType type = property.getType();
    return property.getMember() != null
        && (type.isContainerType() || type.isReferenceType())
        && ANNOTATIONS.findPropertyContentTypeResolver(CONFIG, property.getMember(), type) != null;
  }

  /** Whether {@code member} is annotated {@code @Nullable}, from whichever package. */
  private static boolean hasNullableAnnotation(@Nullable AnnotatedMember member) {
    if (member == null || member.getAnnotated() == null) {
      return false;
    }
    AnnotationMap declared = member.getAllAnnotations();
    return Stream.concat(
            declared == null ? Stream.empty() : Streams.stream(declared.annotations()),
            Arrays.stream(typeAnnotations(member.getAnnotated())))
        .anyMatch(annotation -> annotation.annotationType().getSimpleName().equals("Nullable"));
  }

  /**
   * Returns the annotations on the type of a getter or field, where a type-use annotation such as
   * JSpecify's sits. Android has no {@code AnnotatedType}, so this calls the JDK reflectively.
   */
  private static Annotation[] typeAnnotations(AnnotatedElement element) {
    String accessor = element instanceof Method ? "getAnnotatedReturnType" : "getAnnotatedType";
    try {
      return ((AnnotatedElement) element.getClass().getMethod(accessor).invoke(element))
          .getAnnotations();
    } catch (ReflectiveOperationException e) {
      return new Annotation[0];
    }
  }

  /**
   * Whether {@code property} has {@code @JsonProperty(isRequired = OptBoolean.FALSE)}; Jackson
   * cannot tell {@code required = false} from that attribute's default.
   */
  private static boolean isMarkedNotRequired(PropertyWriter property) {
    JsonProperty annotation = property.getAnnotation(JsonProperty.class);
    return annotation != null && annotation.isRequired() == OptBoolean.FALSE;
  }

  private static Schema typed(Type.Known type) {
    return Schema.builder().type(type).build();
  }
}
