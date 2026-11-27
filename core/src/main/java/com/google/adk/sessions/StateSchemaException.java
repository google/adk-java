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

package com.google.adk.sessions;

import com.google.adk.annotations.Experimental;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a workflow's state schema rejects a state write: the schema does not declare the key,
 * or the value does not match the key's schema. The message never quotes the value.
 */
@Experimental
public final class StateSchemaException extends IllegalArgumentException {

  public StateSchemaException(String message) {
    super(message);
  }

  public StateSchemaException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
