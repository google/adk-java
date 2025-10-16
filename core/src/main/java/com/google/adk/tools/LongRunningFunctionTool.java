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

import java.lang.reflect.Method;

/** A function tool that returns the result asynchronously. */
public class LongRunningFunctionTool extends FunctionTool {

  public static LongRunningFunctionTool create(Method func) {
    return new LongRunningFunctionTool(func);
  }

  public static LongRunningFunctionTool create(Class<?> cls, String methodName) {
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(methodName)) {
        return create(method);
      }
    }
    throw new IllegalArgumentException(
        String.format("Method %s not found in class %s.", methodName, cls.getName()));
  }

  public static LongRunningFunctionTool create(Object instance, String methodName) {
    Class<?> cls = instance.getClass();
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(methodName)) {
        return new LongRunningFunctionTool(instance, method);
      }
    }
    throw new IllegalArgumentException(
        String.format("Method %s not found in class %s.", methodName, cls.getName()));
  }

  private LongRunningFunctionTool(Method func) {
    super(null, func, /* isLongRunning= */ true, /* requireConfirmation= */ false);
  }

  private LongRunningFunctionTool(Object instance, Method func) {
    super(instance, func, /* isLongRunning= */ true, /* requireConfirmation= */ false);
  }
}
