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

package com.google.adk.tools.mcp;

/** Base exception for all errors originating from {@code AbstractMcpTool} and its subclasses. */
public class McpToolException extends RuntimeException {

  public McpToolException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Exception thrown when there's an error during MCP tool declaration generated. */
  public static class McpToolDeclarationException extends McpToolException {
    public McpToolDeclarationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
