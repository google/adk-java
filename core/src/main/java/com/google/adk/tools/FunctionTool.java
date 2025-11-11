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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Flowable;
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
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** FunctionTool implements a customized function calling tool. */
public class FunctionTool extends BaseTool {

  private static final Logger logger = LoggerFactory.getLogger(FunctionTool.class);
  private static final ObjectMapper OBJECT_MAPPER = JsonBaseModel.getMapper();

  private final @Nullable Object instance;
  private final Method func;
  private final FunctionDeclaration funcDeclaration;
  private final boolean requireConfirmation;

  public static FunctionTool create(Object instance, Method func) {
    return create(instance, func, /* requireConfirmation= */ false);
  }

  public static FunctionTool create(Object instance, Method func, boolean requireConfirmation) {
    if (!areParametersAnnotatedWithSchema(func) && wasCompiledWithDefaultParameterNames(func)) {
      logger.error(
          """
          Functions used in tools must have their parameters annotated with @Schema or at least
           the code must be compiled with the -parameters flag as a fallback. Your function
           tool will likely not work as expected and exit at runtime.
          """);
    }
    if (!Modifier.isStatic(func.getModifiers()) && !func.getDeclaringClass().isInstance(instance)) {
      throw new IllegalArgumentException(
          String.format(
              "The instance provided is not an instance of the declaring class of the method."
                  + " Expected: %s, Actual: %s",
              func.getDeclaringClass().getName(), instance.getClass().getName()));
    }
    return new FunctionTool(
        instance, func, /* isLongRunning= */ false, /* requireConfirmation= */ requireConfirmation);
  }

  public static FunctionTool create(Method func) {
    return create(func, /* requireConfirmation= */ false);
  }

  public static FunctionTool create(Method func, boolean requireConfirmation) {
    if (!areParametersAnnotatedWithSchema(func) && wasCompiledWithDefaultParameterNames(func)) {
      logger.error(
          """
          Functions used in tools must have their parameters annotated with @Schema or at least
           the code must be compiled with the -parameters flag as a fallback. Your function
           tool will likely not work as expected and exit at runtime.
          """);
    }
    if (!Modifier.isStatic(func.getModifiers())) {
      throw new IllegalArgumentException("The method provided must be static.");
    }
    return new FunctionTool(null, func, /* isLongRunning= */ false, requireConfirmation);
  }

  public static FunctionTool create(Class<?> cls, String methodName) {
    return create(cls, methodName, /* requireConfirmation= */ false);
  }

  public static FunctionTool create(Class<?> cls, String methodName, boolean requireConfirmation) {
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(methodName) && Modifier.isStatic(method.getModifiers())) {
        return create(null, method, requireConfirmation);
      }
    }
    throw new IllegalArgumentException(
        String.format("Static method %s not found in class %s.", methodName, cls.getName()));
  }

  public static FunctionTool create(Object instance, String methodName) {
    return create(instance, methodName, /* requireConfirmation= */ false);
  }

  public static FunctionTool create(
      Object instance, String methodName, boolean requireConfirmation) {
    Class<?> cls = instance.getClass();
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(methodName) && !Modifier.isStatic(method.getModifiers())) {
        return create(instance, method, requireConfirmation);
      }
    }
    throw new IllegalArgumentException(
        String.format("Instance method %s not found in class %s.", methodName, cls.getName()));
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

  protected FunctionTool(@Nullable Object instance, Method func, boolean isLongRunning) {
    this(instance, func, isLongRunning, /* requireConfirmation= */ false);
  }

  protected FunctionTool(
      @Nullable Object instance, Method func, boolean isLongRunning, boolean requireConfirmation) {
    super(
        func.isAnnotationPresent(Annotations.Schema.class)
                && !func.getAnnotation(Annotations.Schema.class).name().isEmpty()
            ? func.getAnnotation(Annotations.Schema.class).name()
            : func.getName(),
        func.isAnnotationPresent(Annotations.Schema.class)
            ? func.getAnnotation(Annotations.Schema.class).description()
            : "",
        isLongRunning);
    boolean isStatic = Modifier.isStatic(func.getModifiers());
    if (isStatic && instance != null) {
      throw new IllegalArgumentException("Static function tool must not have an instance.");
    } else if (!isStatic && instance == null) {
      throw new IllegalArgumentException("Instance function tool must have an instance.");
    }

    this.instance = instance;
    this.func = func;
    this.funcDeclaration =
        FunctionCallingUtils.buildFunctionDeclaration(
            this.func, ImmutableList.of("toolContext", "inputStream"));
    this.requireConfirmation = requireConfirmation;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(this.funcDeclaration);
  }

  /** Returns the underlying function {@link Method}. */
  public Method func() {
    return func;
  }

  /** Returns true if the wrapped function returns a Flowable and can be used for streaming. */
  public boolean isStreaming() {
    Type returnType = func.getGenericReturnType();
    if (returnType instanceof ParameterizedType parameterizedType) {
      if (parameterizedType.getRawType() instanceof Class<?> rawType) {
        return Flowable.class.isAssignableFrom(rawType);
      }
    }
    return false;
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    try {
      if (requireConfirmation) {
        if (toolContext.toolConfirmation().isEmpty()) {
          toolContext.requestConfirmation(
              String.format(
                  "Please approve or reject the tool call %s() by responding with a"
                      + " FunctionResponse with an expected ToolConfirmation payload.",
                  name()));
          return Single.just(
              ImmutableMap.of(
                  "error", "This tool call requires confirmation, please approve or reject."));
        } else if (!toolContext.toolConfirmation().get().confirmed()) {
          return Single.just(ImmutableMap.of("error", "This tool call is rejected."));
        }
      }
      return this.call(args, toolContext).defaultIfEmpty(ImmutableMap.of());
    } catch (Exception e) {
      logger.error("Exception occurred while calling function tool: " + func.getName(), e);
      return Single.just(
          ImmutableMap.of("status", "error", "message", "An internal error occurred."));
    }
  }

  private Maybe<Map<String, Object>> call(Map<String, Object> args, ToolContext toolContext)
      throws IllegalAccessException, InvocationTargetException {
    Object[] arguments = buildArguments(args, toolContext, null);
    Object result = func.invoke(instance, arguments);
    if (result == null) {
      return Maybe.empty();
    } else if (result instanceof Maybe) {
      return ((Maybe<?>) result)
          .map(
              data ->
                  OBJECT_MAPPER.convertValue(data, new TypeReference<Map<String, Object>>() {}));
    } else if (result instanceof Single) {
      return ((Single<?>) result)
          .map(
              data -> OBJECT_MAPPER.convertValue(data, new TypeReference<Map<String, Object>>() {}))
          .toMaybe();
    } else {
      return Maybe.just(
          OBJECT_MAPPER.convertValue(result, new TypeReference<Map<String, Object>>() {}));
    }
  }

  @SuppressWarnings("unchecked")
  public Flowable<Map<String, Object>> callLive(
      Map<String, Object> args, ToolContext toolContext, InvocationContext invocationContext)
      throws IllegalAccessException, InvocationTargetException {
    Object[] arguments = buildArguments(args, toolContext, invocationContext);
    Object result = func.invoke(instance, arguments);
    if (result instanceof Flowable) {
      return (Flowable<Map<String, Object>>) result;
    } else {
      throw new IllegalArgumentException(
          "callLive was called but the underlying function does not return a Flowable.");
    }
  }

  @SuppressWarnings("unchecked") // For tool parameter type casting.
  private Object[] buildArguments(
      Map<String, Object> args,
      ToolContext toolContext,
      @Nullable InvocationContext invocationContext) {
    Parameter[] parameters = func.getParameters();
    Object[] arguments = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      String paramName =
          parameters[i].isAnnotationPresent(Annotations.Schema.class)
                  && !parameters[i].getAnnotation(Annotations.Schema.class).name().isEmpty()
              ? parameters[i].getAnnotation(Annotations.Schema.class).name()
              : parameters[i].getName();
      if ("toolContext".equals(paramName)) {
        arguments[i] = toolContext;
        continue;
      }
      if ("inputStream".equals(paramName)) {
        if (invocationContext != null
            && invocationContext.activeStreamingTools().containsKey(this.name())
            && invocationContext.activeStreamingTools().get(this.name()).stream() != null) {
          arguments[i] = invocationContext.activeStreamingTools().get(this.name()).stream();
        } else {
          arguments[i] = null;
        }
        continue;
      }
      Annotations.Schema schema = parameters[i].getAnnotation(Annotations.Schema.class);
      if (!args.containsKey(paramName)) {
        if (schema != null && schema.optional()) {
          arguments[i] = null;
          continue;
        } else {
          throw new IllegalArgumentException(
              String.format(
                  "The parameter '%s' was not found in the arguments provided by the model.",
                  paramName));
        }
      }
      Class<?> paramType = parameters[i].getType();
      Object argValue = args.get(paramName);
      if (paramType.equals(List.class)) {
        if (argValue instanceof List) {
          Type type =
              ((ParameterizedType) parameters[i].getParameterizedType())
                  .getActualTypeArguments()[0];
          Class<?> typeArgClass = getTypeClass(type, paramName);
          arguments[i] = createList((List<Object>) argValue, typeArgClass);
          continue;
        }
      } else if (argValue instanceof Map) {
        arguments[i] = OBJECT_MAPPER.convertValue(argValue, paramType);
        continue;
      }
      arguments[i] = castValue(argValue, paramType);
    }
    return arguments;
  }

  private static Class<?> getTypeClass(Type type, String paramName) {
    if (type instanceof Class) {
      // Case 1: The argument is a simple class like String, Integer, etc.
      return (Class<?>) type;
    } else if (type instanceof ParameterizedType pType) {
      // Case 2: The argument is another parameterized type like Map<String, Integer>
      return (Class<?>) pType.getRawType(); // Get the raw class (e.g., Map)
    } else {
      throw new IllegalArgumentException(
          String.format("Unsupported parameterized type %s for '%s'", type, paramName));
    }
  }

  private static List<Object> createList(List<Object> values, Class<?> type) {
    List<Object> list = new ArrayList<>();
    // List of parameterized type is not supported.
    if (type == null) {
      return list;
    }
    Class<?> cls = type;
    for (Object value : values) {
      if (cls == Integer.class
          || cls == Long.class
          || cls == Double.class
          || cls == Float.class
          || cls == Boolean.class
          || cls == String.class) {
        list.add(castValue(value, cls));
      } else {
        list.add(OBJECT_MAPPER.convertValue(value, type));
      }
    }
    return list;
  }

  private static Object castValue(Object value, Class<?> type) {
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
      if (value instanceof Double d) {
        return d.doubleValue();
      }
      if (value instanceof Float f) {
        return f.doubleValue();
      }
      if (value instanceof Integer i) {
        return i.doubleValue();
      }
      if (value instanceof Long l) {
        return l.doubleValue();
      }
    } else if (type.equals(Float.class) || type.equals(float.class)) {
      if (value instanceof Double d) {
        return d.floatValue();
      }
      if (value instanceof Float f) {
        return f.floatValue();
      }
      if (value instanceof Integer i) {
        return i.floatValue();
      }
      if (value instanceof Long l) {
        return l.floatValue();
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
