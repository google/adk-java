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

/** Enum for the type of A2A DataPart metadata. */
public enum A2ADataPartMetadataType {
  FUNCTION_RESPONSE("function_response"),
  FUNCTION_CALL("function_call"),
  CODE_EXECUTION_RESULT("code_execution_result"),
  EXECUTABLE_CODE("executable_code");

  private final String type;

  private A2ADataPartMetadataType(String type) {
    this.type = type;
  }

  public String getType() {
    return type;
  }
}
