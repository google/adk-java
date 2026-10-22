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

package com.google.adk.tools.mcp;

import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Excludes MCP server tools that collide with ADK framework tools. */
final class McpToolNameFilter {
  private static final Logger logger = LoggerFactory.getLogger(McpToolNameFilter.class);

  // Keep these framework-reserved names aligned with ADK Python's MCP toolset.
  private static final ImmutableSet<String> RESERVED_NAMES =
      ImmutableSet.of(
          "set_model_response",
          "transfer_to_agent",
          "adk_request_confirmation",
          "adk_request_credential",
          "adk_request_input");

  private McpToolNameFilter() {}

  static boolean isAllowed(String toolName) {
    if (RESERVED_NAMES.contains(toolName)) {
      logger.warn(
          "Skipping MCP tool '{}' because it collides with a reserved ADK framework tool name.",
          toolName);
      return false;
    }
    return true;
  }
}
