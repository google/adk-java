/*
 * Copyright 2025 Google LLC
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

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.google.adk.JsonBaseModel;
import com.google.common.base.Strings;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for function calling. */
public final class FunctionCallingUtils {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Logger logger = LoggerFactory.getLogger(FunctionCallingUtils.class);

  /** Holds the state during a single schema generation process to handle caching and recursion. */
  private static class SchemaGenerationContext {
    private final Map<String, Schema> definitions = new LinkedHashMap<>();
    private final Set<Type> processingStack = new HashSet<>();

    boolean isProcessing(Type type) {
      return processingStack.contains(type);
    }

    void startProcessing(Type type) {
      processingStack.add(type);
    }

    void finishProcessing(Type type) {
      processingStack.remove(type);
    }

    Optional<Schema> getDefinition(String name) {
      return Optional.ofNullable(definitions.get(name));
    }

    void addDefinition(String name, Schema schema) {
      definitions.put(name, schema);
    }
  }

  /**
   * Builds a FunctionDeclaration from a Java Method, ignoring parameters with the given names.
   *
   * @param func The Java {@link Method} to convert into a FunctionDeclaration.
   * @param ignoreParams The names of parameters to ignore.
   * @return The generated {@link FunctionDeclaration}.
   * @throws IllegalArgumentException if a type is encountered that cannot be serialized by Jackson.
   */
  public static FunctionDeclaration buildFunctionDeclaration(
      Method func, List<String> ignoreParams) {
    String name =
        func.isAnnotationPresent(Annotations.Schema.class)
                && !func.getAnnotation(Annotations.Schema.class).name().isEmpty()
            ? func.getAnnotation(Annotations.Schema.class).name()
            : func.getName();
    FunctionDeclaration.Builder builder = FunctionDeclaration.builder().name(name);
    if (func.isAnnotationPresent(Annotations.Schema.class)
        && !func.getAnnotation(Annotations.Schema.class).description().isEmpty()) {
      builder.description(func.getAnnotation(Annotations.Schema.class).description());
    }
    List<String> required = new ArrayList<>();
    Map<String, Schema> properties = new LinkedHashMap<>();
    for (Parameter param : func.getParameters()) {
      String paramName =
          param.isAnnotationPresent(Annotations.Schema.class)
                  && !param.getAnnotation(Annotations.Schema.class).name().isEmpty()
              ? param.getAnnotation(Annotations.Schema.class).name()
              : param.getName();
      if (ignoreParams.contains(paramName)) {
        continue;
      }
      required.add(paramName);
      properties.put(paramName, buildSchemaFromParameter(param));
    }
    builder.parameters(
        Schema.builder().required(required).properties(properties).type("OBJECT").build());

    Type returnType = func.getGenericReturnType();
    if (returnType != Void.TYPE) {
      Type actualReturnType = returnType;
      if (returnType instanceof ParameterizedType parameterizedReturnType) {
        String rawTypeName = ((Class<?>) parameterizedReturnType.getRawType()).getName();
        if (rawTypeName.equals("io.reactivex.rxjava3.core.Maybe")
            || rawTypeName.equals("io.reactivex.rxjava3.core.Single")
            || rawTypeName.equals("io.reactivex.rxjava3.core.Flowable")) {
          actualReturnType = parameterizedReturnType.getActualTypeArguments()[0];
        }
      }
      builder.response(buildSchemaFromType(actualReturnType));
    }
    return builder.build();
  }

  static FunctionDeclaration buildFunctionDeclaration(JsonBaseModel func, String description) {
    // Create function declaration through json string.
    String jsonString = func.toJson();
    checkArgument(!Strings.isNullOrEmpty(jsonString), "Input String can't be null or empty.");
    FunctionDeclaration declaration = FunctionDeclaration.fromJson(jsonString);
    declaration = declaration.toBuilder().description(description).build();
    if (declaration.name().isEmpty() || declaration.name().get().isEmpty()) {
      throw new IllegalArgumentException("name field must be present.");
    }
    return declaration;
  }

  private static Schema buildSchemaFromParameter(Parameter param) {
    Schema schema = buildSchemaFromType(param.getParameterizedType());
    if (param.isAnnotationPresent(Annotations.Schema.class)
        && !param.getAnnotation(Annotations.Schema.class).description().isEmpty()) {
      return schema.toBuilder()
          .description(param.getAnnotation(Annotations.Schema.class).description())
          .build();
    }
    return schema;
  }

  /**
   * Builds a Schema from a Java Type, creating a new context for the generation process.
   *
   * @param type The Java {@link Type} to convert into a Schema.
   * @return The generated {@link Schema}.
   * @throws IllegalArgumentException if a type is encountered that cannot be serialized by Jackson.
   */
  public static Schema buildSchemaFromType(Type type) {
    return buildSchemaRecursive(type, new SchemaGenerationContext());
  }

  /**
   * Recursively builds a Schema from a Java Type using a context to manage recursion and caching.
   *
   * @param type The Java {@link Type} to convert.
   * @param context The {@link SchemaGenerationContext} for this generation task.
   * @return The generated {@link Schema}.
   * @throws IllegalArgumentException if a type is encountered that cannot be serialized by Jackson.
   */
  @SuppressWarnings("deprecation") // We don't have actual instances of the type
  private static Schema buildSchemaRecursive(Type type, SchemaGenerationContext context) {
    String definitionName = getTypeKey(type);

    if (definitionName != null) {
      if (context.isProcessing(type)) {
        logger.warn("Type {} is recursive. Omitting from schema.", type);
        return Schema.builder()
            .type("OBJECT")
            .description("Recursive reference to " + definitionName + " omitted.")
            .build();
      }
      Optional<Schema> cachedSchema = context.getDefinition(definitionName);
      if (cachedSchema.isPresent()) {
        return cachedSchema.get();
      }
    }

    context.startProcessing(type);

    Schema resultSchema;
    try {
      Schema.Builder builder = Schema.builder();
      if (type instanceof ParameterizedType parameterizedType) {
        Class<?> rawClass = (Class<?>) parameterizedType.getRawType();
        if (List.class.isAssignableFrom(rawClass)) {
          Schema itemSchema =
              buildSchemaRecursive(parameterizedType.getActualTypeArguments()[0], context);
          builder.type("ARRAY").items(itemSchema);
        } else if (Map.class.isAssignableFrom(rawClass)) {
          builder.type("OBJECT");
        } else {
          // Fallback for other parameterized types (e.g., custom generics) is to inspect the
          // raw type.
          return buildSchemaRecursive(rawClass, context);
        }
      } else if (type instanceof Class<?> clazz) {
        if (clazz.isEnum()) {
          builder.type("STRING");
          List<String> enumValues = new ArrayList<>();
          for (Object enumConstant : clazz.getEnumConstants()) {
            enumValues.add(enumConstant.toString());
          }
          builder.enum_(enumValues);
        } else if (String.class.equals(clazz)) {
          builder.type("STRING");
        } else if (Boolean.class.equals(clazz) || boolean.class.equals(clazz)) {
          builder.type("BOOLEAN");
        } else if (Integer.class.equals(clazz) || int.class.equals(clazz)) {
          builder.type("INTEGER");
        } else if (Double.class.equals(clazz)
            || double.class.equals(clazz)
            || Float.class.equals(clazz)
            || float.class.equals(clazz)
            || Long.class.equals(clazz)
            || long.class.equals(clazz)) {
          builder.type("NUMBER");
        } else if (Map.class.isAssignableFrom(clazz)) {
          builder.type("OBJECT");
        } else {
          // Default to treating as a POJO.
          if (!OBJECT_MAPPER.canSerialize(clazz)) {
            throw new IllegalArgumentException(
                "Unsupported type: "
                    + clazz.getName()
                    + ". The type must be a Jackson-serializable POJO or a registered"
                    + " primitive. Opaque types like Protobuf models are not supported"
                    + " directly.");
          }
          BeanDescription beanDescription =
              OBJECT_MAPPER.getSerializationConfig().introspect(OBJECT_MAPPER.constructType(type));
          Map<String, Schema> properties = new LinkedHashMap<>();
          for (BeanPropertyDefinition property : beanDescription.findProperties()) {
            Type propertyType = property.getRawPrimaryType();
            if (propertyType == null) {
              continue;
            }
            properties.put(property.getName(), buildSchemaRecursive(propertyType, context));
          }
          builder.type("OBJECT").properties(properties);
        }
      }
      resultSchema = builder.build();
    } finally {
      context.finishProcessing(type);
    }

    if (definitionName != null) {
      context.addDefinition(definitionName, resultSchema);
    }
    return resultSchema;
  }

  /**
   * Gets a stable, canonical name for a type to use as a key for caching and recursion tracking.
   *
   * @param type The type to name.
   * @return The canonical name of the type, or null if the type should not be tracked (e.g.,
   *     primitives).
   */
  @Nullable
  private static String getTypeKey(Type type) {
    if (type instanceof Class<?> clazz) {
      if (clazz.isPrimitive() || clazz.isEnum() || clazz.getName().startsWith("java.")) {
        return null;
      }
      return clazz.getCanonicalName();
    }
    if (type instanceof ParameterizedType pType) {
      return getTypeKey(pType.getRawType());
    }
    return null;
  }

  private FunctionCallingUtils() {}
}
