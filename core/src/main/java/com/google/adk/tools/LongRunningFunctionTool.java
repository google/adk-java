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
import com.google.adk.utils.ComponentRegistry;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/** A function tool that returns the result asynchronously. */
public class LongRunningFunctionTool extends FunctionTool {

  public static LongRunningFunctionTool create(Method func) {
    return create(func, /* requireConfirmation= */ false);
  }

  public static LongRunningFunctionTool create(Method func, boolean requireConfirmation) {
    return new LongRunningFunctionTool(func, requireConfirmation);
  }

  public static LongRunningFunctionTool create(Class<?> cls, String methodName) {
    return create(cls, methodName, /* requireConfirmation= */ false);
  }

  public static LongRunningFunctionTool create(
      Class<?> cls, String methodName, boolean requireConfirmation) {
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(methodName)) {
        return create(method, requireConfirmation);
      }
    }
    throw new IllegalArgumentException(
        String.format("Method %s not found in class %s.", methodName, cls.getName()));
  }

  public static LongRunningFunctionTool create(Object instance, String methodName) {
    return create(instance, methodName, /* requireConfirmation= */ false);
  }

  public static LongRunningFunctionTool create(
      Object instance, String methodName, boolean requireConfirmation) {
    Class<?> cls = instance.getClass();
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(methodName)) {
        return create(instance, method, requireConfirmation);
      }
    }
    throw new IllegalArgumentException(
        String.format("Method %s not found in class %s.", methodName, cls.getName()));
  }

  public static LongRunningFunctionTool create(@Nullable Object instance, Method method) {
    return create(instance, method, false);
  }

  public static LongRunningFunctionTool create(
      @Nullable Object instance, Method method, boolean requireConfirmation) {
    return new LongRunningFunctionTool(instance, method, requireConfirmation);
  }

  /** Creates a LongRunningFunctionTool from a FunctionTool. */
  public static LongRunningFunctionTool create(FunctionTool tool) {
    return create(tool.instance(), tool.func(), tool.requireConfirmation());
  }

  private LongRunningFunctionTool(Method func, boolean requireConfirmation) {
    super(null, func, /* isLongRunning= */ true, requireConfirmation);
  }

  private LongRunningFunctionTool(
      @Nullable Object instance, Method func, boolean requireConfirmation) {
    super(instance, func, /* isLongRunning= */ true, requireConfirmation);
  }

  public static LongRunningFunctionTool fromConfig(ToolArgsConfig config, String configAbsPath) {
    String funcName =
        config
            .getOrEmpty("func", new TypeReference<String>() {})
            .orElseThrow(
                () ->
                    new IllegalArgumentException("\"func\" argument should be name of a function"));

    FunctionTool funcTool =
        ComponentRegistry.getInstance()
            .get(funcName, FunctionTool.class)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "failed to find FunctionTool \"%s\" in the ComponentRegistry",
                            funcName)));

    return create(funcTool);
  }
}
