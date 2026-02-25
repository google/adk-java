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

package com.google.adk.utils;

import com.google.common.base.Strings;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModelNameUtils {
  private static final String GEMINI_PREFIX = "gemini-";
  private static final Pattern GEMINI_2_PATTERN = Pattern.compile("^gemini-2\\..*");
  private static final String GEMINI_CLASS = "com.google.adk.models.Gemini";
  private static final Pattern PATH_PATTERN =
      Pattern.compile("^projects/[^/]+/locations/[^/]+/publishers/[^/]+/models/(.+)$");
  private static final Pattern APIGEE_PATTERN =
      Pattern.compile("^apigee/(?:[^/]+/)?(?:[^/]+/)?(.+)$");

  public static boolean isGeminiModel(String modelString) {
    return extractModelName(Strings.nullToEmpty(modelString)).startsWith(GEMINI_PREFIX);
  }

  public static boolean isGemini2Model(String modelString) {
    if (modelString == null) {
      return false;
    }
    String modelName = extractModelName(modelString);
    return GEMINI_2_PATTERN.matcher(modelName).matches();
  }

  /**
   * Checks whether an object is an instance of {@link com.google.adk.models.Gemini}, by searching
   * through its class hierarchy for a class whose name equals the hardcoded String name of Gemini
   * class.
   *
   * <p>This method can be used where the "real" instanceof check is not possible because the Gemini
   * type is not known at compile time.
   *
   * @param o The object to check.
   * @return true if object's class is {@link com.google.adk.models.Gemini}, false otherwise.
   */
  public static boolean isInstanceOfGemini(Object o) {
    if (o == null) {
      return false;
    }
    for (Class<?> clazz = o.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
      if (Objects.equals(clazz.getName(), GEMINI_CLASS)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Extract the actual model name from either simple or path-based format.
   *
   * @param modelString Either a simple model name like "gemini-2.5-pro" or a path-based model name
   *     like "projects/.../models/gemini-2.0-flash-001"
   * @return The extracted model name (e.g., "gemini-2.5-pro")
   */
  private static String extractModelName(String modelString) {
    Matcher matcher = PATH_PATTERN.matcher(modelString);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    Matcher apigeeMatcher = APIGEE_PATTERN.matcher(modelString);
    if (apigeeMatcher.matches()) {
      return apigeeMatcher.group(1);
    }
    return modelString;
  }

  private ModelNameUtils() {}
}
