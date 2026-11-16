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
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.AccessorNamingStrategy;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.introspect.ClassIntrospector;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.adk.JsonBaseModel;
import com.google.common.collect.MapMaker;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * Isolated tool views, reused while the source mapper's configuration is unchanged.
 *
 * <p>Module registration and immutable mapper configuration changes (including features and naming
 * strategies) invalidate a view.
 *
 * <p>Mix-ins, subtype registrations, default visibility/inclusion/setter/mergeable/leniency
 * settings, {@code configOverride(Class)}, {@code coercionConfigFor(...)} and {@code
 * coercionConfigDefaults()} must be configured before the first tool or schema operation. Their
 * in-place mutations are not tracked. As with Jackson itself, complete configuration before
 * concurrent use; concurrent reconfiguration and mutation of introspectors or module internals are
 * not supported.
 *
 * <p>View selection follows declared property types only. Annotations found only on polymorphic
 * subtypes (including Jackson-declared/registered subtypes) or runtime values behind {@code Object}
 * or raw containers do not activate a view. Declare the annotated DTO or a typed container directly
 * when property-level annotations are needed.
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

  // A per-operation result, not another cache. Keep the mapper and its configuration key together
  // so declaration generation does not have to check the source a second time.
  record ResolvedMapper(ObjectMapper mapper, Object configuration) {}

  static ResolvedMapper forMethod(ObjectMapper source, Method method) {
    if (isSchemaMapper(source)) {
      return new ResolvedMapper(source, source);
    }
    MapperState state = state(source);
    for (var parameter : method.getParameters()) {
      if (parameter.getType() != ToolContext.class
          && !InputStream.class.isAssignableFrom(parameter.getType())
          && state.requiresSchema(source.constructType(parameter.getParameterizedType()))) {
        return new ResolvedMapper(state.mapper(source), state);
      }
    }
    ObjectMapper mapper =
        state.requiresSchema(source.constructType(method.getGenericReturnType()))
            ? state.mapper(source)
            : source;
    return new ResolvedMapper(mapper, state);
  }

  static boolean isSchemaMapper(ObjectMapper mapper) {
    return mapper.getSerializationConfig().getClassIntrospector()
        instanceof SchemaClassIntrospector;
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
    private final ConcurrentMap<JavaType, Boolean> annotatedTypes = new ConcurrentHashMap<>();
    private @Nullable ObjectMapper mapper;

    MapperState(ObjectMapper source) {
      serialization = source.getSerializationConfig();
      deserialization = source.getDeserializationConfig();
      serializerFactory = source.getSerializerFactory();
      deserializationContext = source.getDeserializationContext();
      injectableValues = source.getInjectableValues();
      modules = Set.copyOf(source.getRegisteredModuleIds());
    }

    boolean matches(ObjectMapper source) {
      return serialization == source.getSerializationConfig()
          && deserialization == source.getDeserializationConfig()
          && serializerFactory == source.getSerializerFactory()
          && deserializationContext == source.getDeserializationContext()
          && injectableValues == source.getInjectableValues()
          && modules.equals(source.getRegisteredModuleIds());
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
      if (raw.isPrimitive() || raw.isEnum() || raw.getName().startsWith("java.")) {
        return false;
      }
      BeanDescription bean = serialization.introspect(type);
      BeanDescription input = deserialization.introspect(type);
      Map<AnnotatedMember, String> names = propertyNames(bean, input, serialization);
      for (AnnotatedMember member : members(bean, input)) {
        if (names.containsKey(member) && member.hasAnnotation(Annotations.Schema.class)) {
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

    synchronized ObjectMapper mapper(ObjectMapper source) {
      if (mapper == null) {
        // ObjectMapper.copy() rejects subclasses that merely inherit it. Copy their configured
        // Jackson state through the protected copy constructor instead; never change the source.
        try {
          Class<?> copyOwner = source.getClass().getMethod("copy").getDeclaringClass();
          mapper =
              (copyOwner == ObjectMapper.class || copyOwner == JsonMapper.class)
                      && source.getClass() != copyOwner
                  ? new MapperCopy(source)
                  : source.copy();
        } catch (NoSuchMethodException e) {
          throw new IllegalStateException("ObjectMapper.copy() is not available", e);
        }
        // Creator construction also asks the mapper-level introspector directly, outside bean
        // property collection. Keep the fallback for that path (notably optional creator inputs).
        SchemaIntrospector schema = new SchemaIntrospector(this, null);
        mapper.setAnnotationIntrospectors(
            AnnotationIntrospector.pair(schema, serialization.getAnnotationIntrospector()),
            AnnotationIntrospector.pair(schema, deserialization.getAnnotationIntrospector()));
        mapper.setConfig(
            mapper
                .getSerializationConfig()
                .with(
                    new SchemaClassIntrospector(
                        this, mapper.getSerializationConfig().getClassIntrospector())));
        mapper.setConfig(
            mapper
                .getDeserializationConfig()
                .with(
                    new SchemaClassIntrospector(
                        this, mapper.getDeserializationConfig().getClassIntrospector())));
      }
      return mapper;
    }
  }

  private static final class MapperCopy extends ObjectMapper {
    MapperCopy(ObjectMapper source) {
      super(source);
    }
  }

  /**
   * Supplies a property introspector scoped to the concrete bean, not a member's declaring class.
   */
  private static final class SchemaClassIntrospector extends ClassIntrospector {
    private final MapperState state;
    private final ClassIntrospector delegate;
    private final ConcurrentMap<JavaType, SchemaIntrospector> schemas = new ConcurrentHashMap<>();

    SchemaClassIntrospector(MapperState state, ClassIntrospector delegate) {
      this.state = state;
      this.delegate = delegate;
    }

    private AnnotationIntrospector annotations(MapperConfig<?> config, JavaType type) {
      return AnnotationIntrospector.pair(
          schemas.computeIfAbsent(type, key -> new SchemaIntrospector(state, key)),
          config.getAnnotationIntrospector());
    }

    @Override
    public ClassIntrospector copy() {
      return new SchemaClassIntrospector(state, delegate.copy());
    }

    @Override
    public BeanDescription forSerialization(
        SerializationConfig config, JavaType type, MixInResolver resolver) {
      return delegate.forSerialization(config.with(annotations(config, type)), type, resolver);
    }

    @Override
    public BeanDescription forDeserialization(
        DeserializationConfig config, JavaType type, MixInResolver resolver) {
      return delegate.forDeserialization(config.with(annotations(config, type)), type, resolver);
    }

    @Override
    public BeanDescription forDeserializationWithBuilder(
        DeserializationConfig config,
        JavaType type,
        MixInResolver resolver,
        BeanDescription valueType) {
      return delegate.forDeserializationWithBuilder(
          config.with(annotations(config, type)), type, resolver, valueType);
    }

    @Override
    public BeanDescription forCreation(
        DeserializationConfig config, JavaType type, MixInResolver resolver) {
      return delegate.forCreation(config.with(annotations(config, type)), type, resolver);
    }

    @Override
    public BeanDescription forClassAnnotations(
        MapperConfig<?> config, JavaType type, MixInResolver resolver) {
      return delegate.forClassAnnotations(config, type, resolver);
    }

    @Override
    public BeanDescription forDirectClassAnnotations(
        MapperConfig<?> config, JavaType type, MixInResolver resolver) {
      return delegate.forDirectClassAnnotations(config, type, resolver);
    }
  }

  private static final class SchemaIntrospector extends NopAnnotationIntrospector {
    // Keep the cached state alive while a FunctionTool is using this view, not just the weak cache.
    private final MapperState state;
    private final @Nullable JavaType type;
    private final ConcurrentMap<JavaType, Map<AnnotatedMember, PropertySchema>> properties =
        new ConcurrentHashMap<>();

    SchemaIntrospector(MapperState state, @Nullable JavaType type) {
      this.state = state;
      this.type = type;
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
      if (!(annotated instanceof AnnotatedMember member)) {
        return null;
      }
      JavaType beanType =
          type != null ? type : state.serialization.constructType(member.getDeclaringClass());
      return properties.computeIfAbsent(beanType, this::propertiesOf).get(member);
    }

    private Map<AnnotatedMember, PropertySchema> propertiesOf(JavaType type) {
      BeanDescription output = state.serialization.introspect(type);
      BeanDescription input = state.deserialization.introspect(type);
      Map<AnnotatedMember, String> names = propertyNames(output, input, state.serialization);

      Map<String, PropertySchema> schemas = new LinkedHashMap<>();
      // Read unmerged annotations so conflicting names cannot be hidden by Jackson's precedence.
      for (AnnotatedMember member : members(output, input)) {
        String name = names.get(member);
        if (name == null) {
          continue;
        }
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
                    + type.getRawClass().getName()
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

  private static Map<AnnotatedMember, String> propertyNames(
      BeanDescription output, BeanDescription input, SerializationConfig config) {
    Map<AnnotatedMember, String> names = new HashMap<>();
    List<BeanPropertyDefinition> definitions = new ArrayList<>(output.findProperties());
    definitions.addAll(input.findProperties());
    for (BeanPropertyDefinition property : definitions) {
      // A setter-shaped tool method alone (for example setAlarm(int)) is not an object property
      // for this feature. Setter annotations can still describe an associated field/getter/creator.
      if (!property.hasField() && !property.hasGetter() && !property.hasConstructorParameter()) {
        continue;
      }
      addName(names, property.getField(), property.getInternalName());
      addName(names, property.getGetter(), property.getInternalName());
      addName(names, property.getSetter(), property.getInternalName());
      property
          .getConstructorParameters()
          .forEachRemaining(parameter -> addName(names, parameter, property.getInternalName()));
    }

    // @Schema may expose a private field/accessor that Jackson otherwise omits. Use Jackson's
    // naming strategy for those members too; never duplicate its bean/record/acronym rules.
    AccessorNamingStrategy naming =
        output.getType().isRecordType()
            ? config.getAccessorNaming().forRecord(config, output.getClassInfo())
            : config.getAccessorNaming().forPOJO(config, output.getClassInfo());
    for (AnnotatedMember member : members(output, input)) {
      if (names.containsKey(member)
          || !member.hasAnnotation(Annotations.Schema.class)
          || Modifier.isStatic(member.getMember().getModifiers())) {
        continue;
      }
      String name = null;
      if (member instanceof AnnotatedField field) {
        name = naming.modifyFieldName(field, field.getName());
      } else if (member instanceof AnnotatedMethod method) {
        if (method.getParameterCount() == 0 && method.getRawReturnType() != void.class) {
          name = naming.findNameForRegularGetter(method, method.getName());
          if (name == null) {
            name = naming.findNameForIsGetter(method, method.getName());
          }
        }
      }
      if (name != null) {
        names.put(member, name);
      }
    }
    return names;
  }

  private ToolSchemaObjectMapper() {}
}
