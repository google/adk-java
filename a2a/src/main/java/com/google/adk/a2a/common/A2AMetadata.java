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
package com.google.adk.a2a.common;

/** Constants and utilities for A2A metadata keys. */
public final class A2AMetadata {

  /** Enum for A2A custom metadata keys. */
  public enum Key {
    REQUEST("a2a:request"),
    RESPONSE("a2a:response"),
    AGGREGATED("a2a:aggregated");

    private final String value;

    Key(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  private A2AMetadata() {}
}
