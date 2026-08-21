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

package com.google.adk.models;

import java.util.UUID;

/** Constants and helpers for ADK-generated function call IDs. */
public final class FunctionCallIds {

  /** Prefix marking function call IDs the ADK generated client-side. */
  private static final String AF_FUNCTION_CALL_ID_PREFIX = "adk-";

  /** Returns a new client-side function call ID with the ADK prefix. */
  public static String generateClientFunctionCallId() {
    return AF_FUNCTION_CALL_ID_PREFIX + UUID.randomUUID();
  }

  /** Returns whether {@code id} was generated client-side by the ADK. */
  public static boolean isClientGeneratedFunctionCallId(String id) {
    return id != null && id.startsWith(AF_FUNCTION_CALL_ID_PREFIX);
  }

  private FunctionCallIds() {}
}
