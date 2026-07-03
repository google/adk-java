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
