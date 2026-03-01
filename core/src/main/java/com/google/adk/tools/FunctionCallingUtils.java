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
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for function calling. */
public final class FunctionCallingUtils {

  private static final Logger logger = LoggerFactory.getLogger(FunctionCallingUtils.class);
  private static final ObjectMapper defaultObjectMapper = JsonBaseModel.getMapper();

  /** Holds the state during a single schema generation process to handle caching and recursion. */
  private static class SchemaGenerationContext {
    private final Map<JavaType, Schema> definitions = new LinkedHashMap<>();
    private final Set<JavaType> processingStack = new HashSet<>();

    boolean isProcessing(JavaType type) {
      return processingStack.contains(type);
    }

    void startProcessing(JavaType type) {
      processingStack.add(type);
    }

    void finishProcessing(JavaType type) {
      processingStack.remove(type);
    }

    Optional<Schema> getDefinition(JavaType type) {
      return Optional.ofNullable(definitions.get(type));
    }

    void addDefinition(JavaType type, Schema schema) {
      definitions.put(type, schema);
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
      Annotations.Schema schema = param.getAnnotation(Annotations.Schema.class);
      if (schema == null || !schema.optional()) {
        required.add(paramName);
      }
      properties.put(paramName, buildSchemaFromParameter(param));
    }
    builder.parameters(
        Schema.builder().required(required).properties(properties).type("OBJECT").build());

    Type returnType = func.getGenericReturnType();
    if (returnType == Void.TYPE || returnType == Void.class) {
      builder.response(Schema.builder().type("NULL").build());
    } else {
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
    return buildSchemaFromType(type, defaultObjectMapper);
  }

  /**
   * Builds a Schema from a Java Type, creating a new context for the generation process.
   *
   * @param type The Java {@link Type} to convert into a Schema.
   * @param objectMapper The {@link ObjectMapper} to use for introspecting types.
   * @return The generated {@link Schema}.
   * @throws IllegalArgumentException if a type is encountered that cannot be serialized by Jackson.
   */
  public static Schema buildSchemaFromType(Type type, ObjectMapper objectMapper) {
    return buildSchemaRecursive(
        objectMapper.constructType(type), new SchemaGenerationContext(), objectMapper);
  }

  /**
   * Recursively builds a Schema from a Java Type using a context to manage recursion and caching.
   *
   * @param javaType The Java {@link JavaType} to convert.
   * @param context The {@link SchemaGenerationContext} for this generation task.
   * @return The generated {@link Schema}.
   * @throws IllegalArgumentException if a type is encountered that cannot be serialized by Jackson.
   */
  private static Schema buildSchemaRecursive(
      JavaType javaType, SchemaGenerationContext context, ObjectMapper objectMapper) {
    if (context.isProcessing(javaType)) {
      logger.warn("Type {} is recursive. Omitting from schema.", javaType.toCanonical());
      return Schema.builder()
          .type("OBJECT")
          .description("Recursive reference to " + javaType.toCanonical() + " omitted.")
          .build();
    }
    Optional<Schema> cachedSchema = context.getDefinition(javaType);
    if (cachedSchema.isPresent()) {
      return cachedSchema.get();
    }

    context.startProcessing(javaType);

    Schema resultSchema;
    try {
      Schema.Builder builder = Schema.builder();
      Class<?> rawClass = javaType.getRawClass();

      if (javaType.isCollectionLikeType() && List.class.isAssignableFrom(rawClass)) {
        builder
            .type("ARRAY")
            .items(buildSchemaRecursive(javaType.getContentType(), context, objectMapper));
      } else if (javaType.isMapLikeType()) {
        builder.type("OBJECT");
      } else if (String.class.equals(rawClass)) {
        builder.type("STRING");
      } else if (Boolean.class.equals(rawClass) || boolean.class.equals(rawClass)) {
        builder.type("BOOLEAN");
      } else if (Integer.class.equals(rawClass) || int.class.equals(rawClass)) {
        builder.type("INTEGER");
      } else if (Double.class.equals(rawClass)
          || double.class.equals(rawClass)
          || Float.class.equals(rawClass)
          || float.class.equals(rawClass)
          || Long.class.equals(rawClass)
          || long.class.equals(rawClass)) {
        builder.type("NUMBER");
      } else if (rawClass.isEnum()) {
        List<String> enumValues = new ArrayList<>();
        for (Object enumConstant : rawClass.getEnumConstants()) {
          enumValues.add(enumConstant.toString());
        }
        builder.enum_(enumValues).type("STRING").format("enum");
      } else { // POJO
        if (!objectMapper.canSerialize(rawClass)) {
          throw new IllegalArgumentException(
              "Unsupported type: "
                  + rawClass.getName()
                  + ". The type must be a Jackson-serializable POJO or a registered"
                  + " primitive. Opaque types like Protobuf models are not supported"
                  + " directly.");
        }
        BeanDescription beanDescription =
            objectMapper.getSerializationConfig().introspect(javaType);
        Map<String, Schema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (BeanPropertyDefinition property : beanDescription.findProperties()) {
          AnnotatedMember member = property.getPrimaryMember();
          if (member != null) {
            properties.put(
                property.getName(), buildSchemaRecursive(member.getType(), context, objectMapper));
            if (property.isRequired()) {
              required.add(property.getName());
            }
          }
        }
        builder.type("OBJECT").properties(properties);
        if (!required.isEmpty()) {
          builder.required(required);
        }
      }
      resultSchema = builder.build();
    } finally {
      context.finishProcessing(javaType);
    }

    context.addDefinition(javaType, resultSchema);
    return resultSchema;
  }

  private FunctionCallingUtils() {}
}
