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

package com.google.adk.plugins.reflectandretry;

import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * The response {@link ReflectAndRetryToolPlugin} substitutes for a failed tool call: what went
 * wrong, how many times it has now failed, and what the model should do differently.
 *
 * <p>Port of adk-python's {@code ToolFailureResponse} pydantic model. The map keys are the
 * snake_case field names that model serializes to; {@link #RESPONSE_TYPE_KEY} is how the plugin
 * recognizes its own output on a later {@code afterToolCallback}.
 */
record ToolFailureResponse(
    String errorType, String errorDetails, int retryCount, String reflectionGuidance) {

  /** Marks a tool result as this plugin's own output rather than a real tool response. */
  static final String REFLECT_AND_RETRY_RESPONSE_TYPE = "ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN";

  static final String RESPONSE_TYPE_KEY = "response_type";

  private static final String ERROR_TYPE_KEY = "error_type";
  private static final String ERROR_DETAILS_KEY = "error_details";
  private static final String RETRY_COUNT_KEY = "retry_count";
  private static final String REFLECTION_GUIDANCE_KEY = "reflection_guidance";

  /** The tool-response map handed back to the flow, matching adk-python's serialization. */
  ImmutableMap<String, Object> toMap() {
    return ImmutableMap.of(
        RESPONSE_TYPE_KEY, REFLECT_AND_RETRY_RESPONSE_TYPE,
        ERROR_TYPE_KEY, errorType,
        ERROR_DETAILS_KEY, errorDetails,
        RETRY_COUNT_KEY, retryCount,
        REFLECTION_GUIDANCE_KEY, reflectionGuidance);
  }

  /** Whether {@code result} is a response this plugin produced earlier. */
  static boolean isReflection(Object result) {
    return result instanceof Map<?, ?> map
        && REFLECT_AND_RETRY_RESPONSE_TYPE.equals(map.get(RESPONSE_TYPE_KEY));
  }
}
