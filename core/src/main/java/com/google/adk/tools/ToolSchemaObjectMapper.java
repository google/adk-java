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
package com.google.adk.tools;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.google.adk.JsonBaseModel;
import com.google.common.collect.MapMaker;
import java.beans.Introspector;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * Isolated tool views, reused while the source mapper's configuration is unchanged.
 *
 * <p>Module registration, feature, naming-strategy and mix-in changes invalidate a view. As with
 * Jackson itself, configure mappers before concurrent use; concurrent reconfiguration and in-place
 * mutation of configuration objects, introspectors, or module internals are not supported.
 */
final class ToolSchemaObjectMapper {
  // Weak identity keys and weak values also handle user modules that refer back to their mapper.
  private static final ConcurrentMap<ObjectMapper, MapperState> states =
      new MapMaker().weakKeys().weakValues().makeMap();
  private static volatile @Nullable MapperState defaultState;

  static ObjectMapper forType(ObjectMapper source, Type type) {
    if (isSchemaMapper(source)) {
      return source;
    }
    MapperState state = state(source);
    return state.requiresSchema(source.constructType(type)) ? state.mapper(source) : source;
  }

  static ObjectMapper forMethod(ObjectMapper source, Method method) {
    if (isSchemaMapper(source)) {
      return source;
    }
    MapperState state = state(source);
    for (var parameter : method.getParameters()) {
      if (parameter.getType() != ToolContext.class
          && !InputStream.class.isAssignableFrom(parameter.getType())
          && state.requiresSchema(source.constructType(parameter.getParameterizedType()))) {
        return state.mapper(source);
      }
    }
    return state.requiresSchema(source.constructType(method.getGenericReturnType()))
        ? state.mapper(source)
        : source;
  }

  static boolean isSchemaMapper(ObjectMapper mapper) {
    return mapper.getSerializationConfig().getAnnotationIntrospector().allIntrospectors().stream()
        .anyMatch(SchemaIntrospector.class::isInstance);
  }

  private static MapperState state(ObjectMapper source) {
    if (source == JsonBaseModel.getMapper()) {
      MapperState cachedDefault = defaultState;
      if (cachedDefault != null && cachedDefault.matches(source)) {
        return cachedDefault;
      }
    }
    MapperState cached = states.get(source);
    if (cached != null && cached.matches(source)) {
      return cached;
    }
    MapperState result =
        states.compute(
            source,
            (key, state) ->
                state != null && state.matches(source) ? state : new MapperState(source));
    if (source == JsonBaseModel.getMapper()) {
      defaultState = result;
    }
    return result;
  }

  private static final class MapperState {
    private final SerializationConfig serialization;
    private final DeserializationConfig deserialization;
    private final Object serializerFactory;
    private final Object deserializationContext;
    private final @Nullable Object injectableValues;
    private final Set<Object> modules;
    private final int mixInCount;
    private final ConcurrentMap<Class<?>, Optional<Class<?>>> mixIns = new ConcurrentHashMap<>();
    private final ConcurrentMap<JavaType, Boolean> annotatedTypes = new ConcurrentHashMap<>();
    private @Nullable ObjectMapper mapper;

    MapperState(ObjectMapper source) {
      serialization = source.getSerializationConfig();
      deserialization = source.getDeserializationConfig();
      serializerFactory = source.getSerializerFactory();
      deserializationContext = source.getDeserializationContext();
      injectableValues = source.getInjectableValues();
      modules = Set.copyOf(source.getRegisteredModuleIds());
      mixInCount = source.mixInCount();
    }

    boolean matches(ObjectMapper source) {
      return serialization == source.getSerializationConfig()
          && deserialization == source.getDeserializationConfig()
          && serializerFactory == source.getSerializerFactory()
          && deserializationContext == source.getDeserializationContext()
          && injectableValues == source.getInjectableValues()
          && modules.equals(source.getRegisteredModuleIds())
          && mixInCount == source.mixInCount()
          && mixIns.entrySet().stream()
              .allMatch(
                  entry ->
                      entry
                          .getValue()
                          .equals(Optional.ofNullable(source.findMixInClassFor(entry.getKey()))));
    }

    boolean requiresSchema(JavaType type) {
      return annotatedTypes.computeIfAbsent(type, key -> requiresSchema(key, new HashSet<>()));
    }

    private boolean requiresSchema(JavaType type, Set<JavaType> visited) {
      if (!visited.add(type)) {
        return false;
      }
      for (int i = 0; i < type.containedTypeCount(); i++) {
        JavaType contained = type.containedType(i);
        if (contained != null && requiresSchema(contained, visited)) {
          return true;
        }
      }
      Class<?> raw = type.getRawClass();
      rememberMixIns(raw);
      if (raw.isPrimitive() || raw.isEnum() || raw.getName().startsWith("java.")) {
        return false;
      }
      BeanDescription bean = serialization.introspect(type);
      BeanDescription input = deserialization.introspect(type);
      for (AnnotatedMember member : members(bean, input)) {
        if (propertyName(member) != null && member.hasAnnotation(Annotations.Schema.class)) {
          return true;
        }
      }
      List<BeanPropertyDefinition> properties = new ArrayList<>(bean.findProperties());
      properties.addAll(input.findProperties());
      for (BeanPropertyDefinition property : properties) {
        if (requiresSchema(property.getPrimaryType(), visited)) {
          return true;
        }
      }
      return false;
    }

    private void rememberMixIns(Class<?> type) {
      if (mixInCount == 0
          || mixIns.putIfAbsent(type, Optional.ofNullable(serialization.findMixInClassFor(type)))
              != null) {
        return;
      }
      if (type.getSuperclass() != null) {
        rememberMixIns(type.getSuperclass());
      }
      for (Class<?> parent : type.getInterfaces()) {
        rememberMixIns(parent);
      }
    }

    synchronized ObjectMapper mapper(ObjectMapper source) {
      if (mapper == null) {
        // ObjectMapper.copy() rejects subclasses that merely inherit it. Copy their configured
        // Jackson state through the protected copy constructor instead; never change the source.
        try {
          mapper =
              source.getClass().getMethod("copy").getDeclaringClass() == ObjectMapper.class
                      && source.getClass() != ObjectMapper.class
                  ? new MapperCopy(source)
                  : source.copy();
        } catch (NoSuchMethodException e) {
          throw new IllegalStateException("ObjectMapper.copy() is not available", e);
        }
        SchemaIntrospector schema = new SchemaIntrospector(this);
        mapper.setAnnotationIntrospectors(
            AnnotationIntrospector.pair(schema, serialization.getAnnotationIntrospector()),
            AnnotationIntrospector.pair(schema, deserialization.getAnnotationIntrospector()));
      }
      return mapper;
    }
  }

  private static final class MapperCopy extends ObjectMapper {
    MapperCopy(ObjectMapper source) {
      super(source);
    }
  }

  private static final class SchemaIntrospector extends NopAnnotationIntrospector {
    // Keep the cached state alive while a FunctionTool is using this view, not just the weak cache.
    private final MapperState state;
    private final ConcurrentMap<Class<?>, Map<AnnotatedMember, PropertySchema>> properties =
        new ConcurrentHashMap<>();

    SchemaIntrospector(MapperState state) {
      this.state = state;
    }

    @Override
    public @Nullable PropertyName findNameForSerialization(Annotated annotated) {
      PropertySchema schema = schemaOf(annotated);
      return schema == null ? null : PropertyName.construct(schema.name());
    }

    @Override
    public @Nullable PropertyName findNameForDeserialization(Annotated annotated) {
      return findNameForSerialization(annotated);
    }

    @Override
    public @Nullable Boolean hasRequiredMarker(AnnotatedMember member) {
      PropertySchema schema = schemaOf(member);
      return schema != null && schema.optional() ? Boolean.FALSE : null;
    }

    @Override
    public @Nullable String findPropertyDescription(Annotated annotated) {
      PropertySchema schema = schemaOf(annotated);
      return schema == null || schema.description().isEmpty() ? null : schema.description();
    }

    private @Nullable PropertySchema schemaOf(Annotated annotated) {
      if (!(annotated instanceof AnnotatedMember member) || propertyName(member) == null) {
        return null;
      }
      return properties.computeIfAbsent(member.getDeclaringClass(), this::propertiesOf).get(member);
    }

    private Map<AnnotatedMember, PropertySchema> propertiesOf(Class<?> type) {
      JavaType javaType = state.serialization.constructType(type);
      BeanDescription output = state.serialization.introspect(javaType);
      BeanDescription input = state.deserialization.introspect(javaType);
      Map<AnnotatedMember, String> names = new HashMap<>();
      List<BeanPropertyDefinition> definitions = new ArrayList<>(output.findProperties());
      definitions.addAll(input.findProperties());
      for (BeanPropertyDefinition property : definitions) {
        addName(names, property.getField(), property.getInternalName());
        addName(names, property.getGetter(), property.getInternalName());
        addName(names, property.getSetter(), property.getInternalName());
        property
            .getConstructorParameters()
            .forEachRemaining(parameter -> addName(names, parameter, property.getInternalName()));
      }

      Map<String, PropertySchema> schemas = new LinkedHashMap<>();
      // Read unmerged annotations so conflicting names cannot be hidden by Jackson's precedence.
      for (AnnotatedMember member : members(output, input)) {
        String implicitName = propertyName(member);
        if (implicitName == null) {
          continue;
        }
        if (member instanceof AnnotatedParameter && !names.containsKey(member)) {
          continue;
        }
        String name = names.computeIfAbsent(member, key -> implicitName);
        Annotations.Schema annotation = member.getAnnotation(Annotations.Schema.class);
        if (annotation != null) {
          PropertySchema previous = schemas.get(name);
          if (previous != null
              && !previous.name().isEmpty()
              && !annotation.name().isEmpty()
              && !previous.name().equals(annotation.name())) {
            throw new IllegalArgumentException(
                "Conflicting @Schema names for property '"
                    + name
                    + "' in "
                    + type.getName()
                    + ": '"
                    + previous.name()
                    + "' and '"
                    + annotation.name()
                    + "'");
          }
          schemas.put(
              name,
              new PropertySchema(
                  previous != null && !previous.name().isEmpty()
                      ? previous.name()
                      : annotation.name(),
                  previous != null && !previous.description().isEmpty()
                      ? previous.description()
                      : annotation.description(),
                  annotation.optional() || (previous != null && previous.optional())));
        }
      }
      Map<AnnotatedMember, PropertySchema> result = new HashMap<>();
      names.forEach(
          (member, name) -> {
            PropertySchema schema = schemas.get(name);
            if (schema != null) {
              result.put(member, schema);
            }
          });
      return result;
    }
  }

  private record PropertySchema(String name, String description, boolean optional) {}

  private static void addName(
      Map<AnnotatedMember, String> names, @Nullable AnnotatedMember member, String name) {
    if (member != null) {
      names.put(member, name);
    }
  }

  private static List<AnnotatedMember> members(BeanDescription output, BeanDescription input) {
    Map<AnnotatedMember, AnnotatedMember> members = new LinkedHashMap<>();
    output.getClassInfo().fields().forEach(member -> members.put(member, member));
    output.getClassInfo().memberMethods().forEach(member -> members.put(member, member));
    input
        .getClassInfo()
        .getConstructors()
        .forEach(
            constructor -> {
              for (int i = 0; i < constructor.getParameterCount(); i++) {
                AnnotatedParameter parameter = constructor.getParameter(i);
                members.put(parameter, parameter);
              }
            });
    input
        .getFactoryMethods()
        .forEach(
            factory -> {
              for (int i = 0; i < factory.getParameterCount(); i++) {
                AnnotatedParameter parameter = factory.getParameter(i);
                members.put(parameter, parameter);
              }
            });
    return new ArrayList<>(members.values());
  }

  private static @Nullable String propertyName(AnnotatedMember member) {
    if (member instanceof AnnotatedParameter parameter) {
      return parameter.getName(); // Only used for parameters Jackson associates with a property.
    }
    if (Modifier.isStatic(member.getMember().getModifiers())) {
      return null;
    }
    if (!(member instanceof AnnotatedMethod method)) {
      return member.getName();
    }
    String name = method.getName();
    if (method.getParameterCount() == 0 && method.getRawType() != void.class) {
      if (name.startsWith("get") && name.length() > 3) {
        return Introspector.decapitalize(name.substring(3));
      }
      if (name.startsWith("is")
          && name.length() > 2
          && (method.getRawType() == boolean.class || method.getRawType() == Boolean.class)) {
        return Introspector.decapitalize(name.substring(2));
      }
      if (member.getDeclaringClass().isRecord()) {
        for (var component : member.getDeclaringClass().getRecordComponents()) {
          if (component.getName().equals(name)) {
            return name;
          }
        }
      }
    }
    return method.getParameterCount() == 1 && name.startsWith("set") && name.length() > 3
        ? Introspector.decapitalize(name.substring(3))
        : null;
  }

  private ToolSchemaObjectMapper() {}
}
