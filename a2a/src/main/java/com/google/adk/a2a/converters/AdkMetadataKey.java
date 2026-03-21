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
package com.google.adk.a2a.converters;

/**
 * Enum for the type of ADK metadata. Adds a prefix used to differentiate A2A-related values stored
 * in custom metadata of an ADK session event.
 */
public enum AdkMetadataKey {
  TASK_ID("task_id"),
  CONTEXT_ID("context_id");

  private final String type;

  private AdkMetadataKey(String type) {
    this.type = "a2a:" + type;
  }

  public String getType() {
    return type;
  }
}
