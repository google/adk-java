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
 * Enum for the type of A2A metadata. Adds a prefix used to differentiage ADK-related values stored
 * in Metadata an A2A event.
 */
public enum A2AMetadataKey {
  TYPE("type"),
  IS_LONG_RUNNING("is_long_running"),
  PARTIAL("partial"),
  GROUNDING_METADATA("grounding_metadata"),
  USAGE_METADATA("usage_metadata"),
  CUSTOM_METADATA("custom_metadata"),
  ERROR_CODE("error_code");

  private final String type;

  private A2AMetadataKey(String type) {
    this.type = "adk_" + type;
  }

  public String getType() {
    return type;
  }
}
