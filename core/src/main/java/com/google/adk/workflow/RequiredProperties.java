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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.impl.PropertyValueBuffer;
import com.fasterxml.jackson.databind.deser.std.StdValueInstantiator;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.google.common.base.Defaults;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Fails a port input that lacks a required creator property of its record or POJO: one the mapper
 * always writes that is not nullable, {@code Optional}, ignored, aliased or marked {@code
 * isRequired = false}. Any other absent primitive property reads as its default.
 */
final class RequiredProperties {

  /** Whether the runtime can read type annotations, where JSpecify's {@code @Nullable} sits. */
  private static final boolean TYPE_ANNOTATIONS_READABLE =
      hasMethod(Field.class, "getAnnotatedType");

  /** Returns {@code instantiator}, made to check the properties it creates a value from. */
  static ValueInstantiator enforce(
      DeserializationConfig config, BeanDescription description, ValueInstantiator instantiator) {
    if (!(instantiator instanceof StdValueInstantiator creator)
        || !creator.canCreateFromObjectWith()) {
      return instantiator;
    }
    SerializationConfig written = PortCodec.MAPPER.getSerializationConfig();
    JavaType type = description.getType();
    BeanDescription writtenDescription = written.introspect(type);
    ImmutableSet<String> required =
        Arrays.stream(creator.getFromObjectArguments(config))
            .filter(property -> isRequired(written, type, writtenDescription, property))
            .map(SettableBeanProperty::getName)
            .collect(toImmutableSet());
    return new Checking(creator, required);
  }

  /**
   * Returns the creator properties {@code instantiator} fails a value without, if it checks any.
   */
  static ImmutableSet<String> required(ValueInstantiator instantiator) {
    return instantiator instanceof Checking checking ? checking.required : ImmutableSet.of();
  }

  private static boolean isRequired(
      SerializationConfig config,
      JavaType type,
      BeanDescription description,
      SettableBeanProperty property) {
    BeanPropertyDefinition written =
        description.findProperties().stream()
            .filter(definition -> definition.getName().equals(property.getName()))
            .findFirst()
            .orElse(null);
    // The mapper never writes a property without a getter or field, so a value may lack it.
    if (written == null || !written.couldSerialize()) {
      return false;
    }
    JsonInclude.Include inclusion =
        description
            .findPropertyInclusion(config.getDefaultPropertyInclusion(type.getRawClass()))
            .withOverrides(written.findInclusion())
            .getValueInclusion();
    JavaType propertyType = property.getType();
    // Without type annotations a JSpecify @Nullable is invisible, so only a primitive is required.
    boolean nullable =
        !propertyType.isPrimitive()
            && (propertyType.isReferenceType()
                || !TYPE_ANNOTATIONS_READABLE
                || hasNullableAnnotation(description, property));
    return (inclusion == JsonInclude.Include.ALWAYS
            || inclusion == JsonInclude.Include.USE_DEFAULTS)
        && !nullable
        && !isMarkedNotRequired(property)
        && property.findAliases(config).isEmpty();
  }

  /**
   * Whether {@code property} is annotated {@code @Nullable}, from whichever package, on the member
   * the mapper uses or on its field, getter, setter or creator parameter.
   */
  static boolean hasNullableAnnotation(BeanDescription description, BeanProperty property) {
    Stream<AnnotatedMember> members =
        description.findProperties().stream()
            .filter(definition -> definition.getName().equals(property.getName()))
            .flatMap(RequiredProperties::members);
    return Stream.concat(Stream.of(property.getMember()), members)
        .filter(member -> member != null && member.getMember() != null)
        .flatMap(
            member ->
                Stream.concat(
                    member.getAllAnnotations() == null
                        ? Stream.empty()
                        : Streams.stream(member.getAllAnnotations().annotations()),
                    Arrays.stream(typeAnnotations(member))))
        .anyMatch(annotation -> annotation.annotationType().getSimpleName().equals("Nullable"));
  }

  /** Returns the field, getter, setter and creator parameter of {@code definition} it has. */
  static Stream<AnnotatedMember> members(BeanPropertyDefinition definition) {
    Stream<AnnotatedMember> members =
        Stream.of(definition.getField(), definition.getConstructorParameter());
    // Jackson rejects overloaded getters or setters it cannot choose between.
    try {
      members = Stream.concat(members, Stream.of(definition.getGetter()));
    } catch (IllegalArgumentException e) {
      // No getter to check.
    }
    try {
      members = Stream.concat(members, Stream.of(definition.getSetter()));
    } catch (IllegalArgumentException e) {
      // No setter to check.
    }
    return members.filter(Objects::nonNull);
  }

  /**
   * Returns the annotations on the type of a field, getter, setter or parameter, where JSpecify's
   * sit. Android has no {@code AnnotatedType}, so this calls the JDK reflectively.
   */
  private static Annotation[] typeAnnotations(AnnotatedMember member) {
    try {
      Object type;
      if (member instanceof AnnotatedParameter parameter) {
        type = annotatedParameterTypes(parameter.getOwner().getMember())[parameter.getIndex()];
      } else if (member.getMember() instanceof Method method) {
        type =
            method.getParameterCount() == 1
                ? annotatedParameterTypes(method)[0]
                : Method.class.getMethod("getAnnotatedReturnType").invoke(method);
      } else {
        type = Field.class.getMethod("getAnnotatedType").invoke(member.getMember());
      }
      return ((AnnotatedElement) type).getAnnotations();
    } catch (ReflectiveOperationException | RuntimeException e) {
      // An inner class's constructor can list fewer annotated types than parameters.
      return new Annotation[0];
    }
  }

  private static boolean hasMethod(Class<?> type, String name) {
    try {
      type.getMethod(name);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static Object[] annotatedParameterTypes(Member executable)
      throws ReflectiveOperationException {
    return (Object[]) Executable.class.getMethod("getAnnotatedParameterTypes").invoke(executable);
  }

  /**
   * Whether {@code property} has {@code @JsonProperty(isRequired = OptBoolean.FALSE)}; Jackson
   * cannot tell {@code required = false} from that attribute's default.
   */
  private static boolean isMarkedNotRequired(BeanProperty property) {
    JsonProperty annotation = property.getAnnotation(JsonProperty.class);
    try {
      return annotation != null && annotation.isRequired() == OptBoolean.FALSE;
    } catch (NoSuchMethodError e) {
      // JsonProperty.isRequired() is new in jackson-annotations 2.19.
      return false;
    }
  }

  /**
   * Checks the properties before Jackson fills in absent ones, which fails for a primitive once
   * null fails for one. Jackson marks a property ignored only after this instantiator is built.
   */
  private static final class Checking extends StdValueInstantiator {
    private final ImmutableSet<String> required;

    Checking(StdValueInstantiator creator, ImmutableSet<String> required) {
      super(creator);
      this.required = required;
    }

    @Override
    public Object createFromObjectWith(
        DeserializationContext context,
        SettableBeanProperty[] properties,
        PropertyValueBuffer buffer)
        throws IOException {
      for (SettableBeanProperty property : properties) {
        if (buffer.hasParameter(property)) {
          continue;
        }
        if (!property.isIgnorable() && required.contains(property.getName())) {
          return context.reportInputMismatch(
              property, "Missing required creator property '%s'", property.getName());
        }
        Class<?> raw = property.getType().getRawClass();
        // Jackson itself fails a property marked required.
        if (raw.isPrimitive() && !property.isRequired()) {
          buffer.assignParameter(property, Defaults.defaultValue(raw));
        }
      }
      return super.createFromObjectWith(context, properties, buffer);
    }
  }

  private RequiredProperties() {}
}
