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

import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Interface for building McpClientTransport instances. Implementations of this interface are
 * responsible for constructing concrete McpClientTransport objects based on the provided connection
 * parameters.
 */
public interface McpTransportBuilder {
  /**
   * Builds an McpClientTransport based on the provided connection parameters.
   *
   * @param connectionParams The parameters required to configure the transport. The type of this
   *     object determines the type of transport built.
   * @return An instance of McpClientTransport.
   * @throws IllegalArgumentException if the connectionParams are not supported or invalid.
   */
  McpClientTransport build(Object connectionParams);
}
