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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.protobuf.InvalidProtocolBufferException;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** FunctionTool implements a customized function calling tool. */
public class FunctionTool extends BaseTool {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Logger logger = LoggerFactory.getLogger(FunctionTool.class);

  private final Method func;
  private FunctionDeclaration funcDeclaration;

  public static FunctionTool create(Method func) {
    if (!areParametersAnnotatedWithSchema(func) && wasCompiledWithDefaultParameterNames(func)) {
      logger.error(
          "Functions used in tools must have their parameters annotated with @Schema or at least"
              + " the code must be compiled with the -parameters flag as a fallback. Your function"
              + " tool will likely not work as expected and exit at runtime.");
    }
    return new FunctionTool(func, /* isLongRunning= */ false);
  }

  private static boolean areParametersAnnotatedWithSchema(Method func) {
    for (Parameter parameter : func.getParameters()) {
      if (!parameter.isAnnotationPresent(Annotations.Schema.class)
          || parameter.getAnnotation(Annotations.Schema.class).name().isEmpty()) {
        return false;
      }
    }
    return true;
  }

  // Rough check to see if the code wasn't compiled with the -parameters flag.
  private static boolean wasCompiledWithDefaultParameterNames(Method func) {
    for (Parameter parameter : func.getParameters()) {
      String parameterName = parameter.getName();
      if (!parameterName.matches("arg\\d+")) {
        return false;
      }
    }
    return true;
  }

  public static FunctionTool create(Class<?> cls, String methodName) {
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(methodName)) {
        return create(method);
      }
    }
    throw new IllegalArgumentException(
        String.format("Method %s not found in class %s.", methodName, cls.getName()));
  }

  protected FunctionTool(Method func, boolean isLongRunning) {
    super(
        func.getAnnotation(Annotations.Schema.class) != null
                && !func.getAnnotation(Annotations.Schema.class).name().isEmpty()
            ? func.getAnnotation(Annotations.Schema.class).name()
            : func.getName(),
        func.getAnnotation(Annotations.Schema.class) != null
            ? func.getAnnotation(Annotations.Schema.class).description()
            : "",
        isLongRunning);
    if (!Modifier.isStatic(func.getModifiers())) {
      throw new IllegalArgumentException("Function tool only supports static methods.");
    }
    this.func = func;
    this.funcDeclaration =
        FunctionCallingUtils.buildFunctionDeclaration(this.func, ImmutableList.of("toolContext"));
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(this.funcDeclaration);
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    try {
      return this.call(args, toolContext).defaultIfEmpty(ImmutableMap.of());
    } catch (Exception e) {
      e.printStackTrace();
      return Single.just(ImmutableMap.of());
    }
  }

  @SuppressWarnings("unchecked") // For tool parameter type casting.
  private Maybe<Map<String, Object>> call(Map<String, Object> args, ToolContext toolContext)
      throws InvalidProtocolBufferException,
          IllegalArgumentException,
          IllegalAccessException,
          InvocationTargetException,
          JsonProcessingException {
    Parameter[] parameters = func.getParameters();
    Object[] arguments = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      String paramName =
          parameters[i].getAnnotation(Annotations.Schema.class) != null
                  && !parameters[i].getAnnotation(Annotations.Schema.class).name().isEmpty()
              ? parameters[i].getAnnotation(Annotations.Schema.class).name()
              : parameters[i].getName();
      if (paramName.equals("toolContext")) {
        arguments[i] = toolContext;
        continue;
      }
      if (!args.containsKey(paramName)) {
        throw new IllegalArgumentException(
            String.format(
                "The parameter '%s' was not found in the arguments provided by the model.",
                paramName));
      }
      Class<?> paramType = parameters[i].getType();
      Object argValue = args.get(paramName);
      if (paramType.equals(List.class)) {
        if (argValue instanceof List) {
          Type type =
              ((ParameterizedType) parameters[i].getParameterizedType())
                  .getActualTypeArguments()[0];
          arguments[i] = createList((List<Object>) argValue, paramName, (Class) type);
          continue;
        }
      } else if (argValue instanceof Map) {
        arguments[i] = OBJECT_MAPPER.convertValue((Map<String, Object>) argValue, paramType);
        continue;
      }
      arguments[i] = castValue(argValue, paramName, paramType);
    }
    Object result = func.invoke(null, arguments);
    if (result == null) {
      return Maybe.empty();
    } else if (result instanceof Maybe) {
      return (Maybe<Map<String, Object>>) result;
    } else {
      return Maybe.just((Map<String, Object>) result);
    }
  }

  @SuppressWarnings("unchecked") // For tool parameter type casting.
  private static List<Object> createList(List<Object> values, String name, Class<?> type)
      throws JsonProcessingException, InvalidProtocolBufferException {
    List<Object> list = new ArrayList<>();
    // List of parameterized type is not supported.
    if (!(type instanceof Class)) {
      return list;
    }
    Class<?> cls = (Class<?>) type;
    for (Object value : values) {
      if (cls == Integer.class
          || cls == Long.class
          || cls == Double.class
          || cls == Float.class
          || cls == Boolean.class
          || cls == String.class) {
        list.add(castValue(value, name, cls));
      } else {
        list.add(OBJECT_MAPPER.convertValue((Map<String, Object>) value, type));
      }
    }
    return list;
  }

  private static Object castValue(Object value, String name, Class<?> type) {
    if (type.equals(Integer.class) || type.equals(int.class)) {
      if (value instanceof Integer) {
        return value;
      }
    }
    if (type.equals(Long.class) || type.equals(long.class)) {
      if (value instanceof Long || value instanceof Integer) {
        return value;
      }
    } else if (type.equals(Double.class) || type.equals(double.class)) {
      if (value instanceof Double) {
        return ((Double) value).doubleValue();
      }
      if (value instanceof Float) {
        return ((Float) value).doubleValue();
      }
      if (value instanceof Integer) {
        return ((Integer) value).doubleValue();
      }
      if (value instanceof Long) {
        return ((Long) value).doubleValue();
      }
    } else if (type.equals(Float.class) || type.equals(float.class)) {
      if (value instanceof Double) {
        return ((Double) value).floatValue();
      }
      if (value instanceof Float) {
        return ((Float) value).floatValue();
      }
      if (value instanceof Integer) {
        return ((Integer) value).floatValue();
      }
      if (value instanceof Long) {
        return ((Long) value).floatValue();
      }
    } else if (type.equals(Boolean.class) || type.equals(boolean.class)) {
      if (value instanceof Boolean) {
        return value;
      }
    } else if (type.equals(String.class)) {
      if (value instanceof String) {
        return value;
      }
    }
    return OBJECT_MAPPER.convertValue(value, type);
  }
}
