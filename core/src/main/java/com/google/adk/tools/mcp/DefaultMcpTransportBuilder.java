package com.google.adk.tools.mcp;

import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.Collection;
import java.util.Optional;

/**
 * The default builder for creating MCP client transports. Supports StdioClientTransport based on
 * {@link ServerParameters} and the standard HttpClientSseClientTransport based on {@link
 * SseServerParameters}.
 */
public class DefaultMcpTransportBuilder implements McpTransportBuilder {

  @Override
  public McpClientTransport build(Object connectionParams) {
    if (connectionParams instanceof ServerParameters serverParameters) {
      return new StdioClientTransport(serverParameters);
    } else if (connectionParams instanceof SseServerParameters sseServerParams) {
      return HttpClientSseClientTransport.builder(sseServerParams.url())
          .sseEndpoint(
              sseServerParams.sseEndpoint() == null ? "sse" : sseServerParams.sseEndpoint())
          .customizeRequest(
              builder ->
                  Optional.ofNullable(sseServerParams.headers())
                      .map(ImmutableMap::entrySet)
                      .stream()
                      .flatMap(Collection::stream)
                      .forEach(
                          entry ->
                              builder.header(
                                  entry.getKey(),
                                  Optional.ofNullable(entry.getValue())
                                      .map(Object::toString)
                                      .orElse(""))))
          .build();
    } else {
      throw new IllegalArgumentException(
          "DefaultMcpTransportBuilder supports only ServerParameters or SseServerParameters, but"
              + " got "
              + connectionParams.getClass().getName());
    }
  }
}
