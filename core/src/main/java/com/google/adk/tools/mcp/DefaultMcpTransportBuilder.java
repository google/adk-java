package com.google.adk.tools.mcp;

import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.util.Collection;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * The default builder for creating MCP client transports. Supports StdioClientTransport based on
 * {@link ServerParameters}, HttpClientSseClientTransport based on {@link SseServerParameters}, and
 * HttpClientStreamableHttpTransport based on {@link StreamableHttpServerParameters}.
 */
public class DefaultMcpTransportBuilder implements McpTransportBuilder {

  private static final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

  @Override
  public McpClientTransport build(Object connectionParams) {
    if (connectionParams instanceof ServerParameters serverParameters) {
      return new StdioClientTransport(serverParameters, jsonMapper);
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
    } else if (connectionParams instanceof StreamableHttpServerParameters streamableParams) {
      return HttpClientStreamableHttpTransport.builder(streamableParams.url())
          .connectTimeout(streamableParams.timeout())
          .jsonMapper(jsonMapper)
          .asyncHttpRequestCustomizer(
              (builder, method, uri, body, context) -> {
                streamableParams.headers().forEach((key, value) -> builder.header(key, value));
                return Mono.just(builder);
              })
          .build();
    } else {
      throw new IllegalArgumentException(
          "DefaultMcpTransportBuilder supports only ServerParameters, SseServerParameters, or"
              + " StreamableHttpServerParameters, but got "
              + connectionParams.getClass().getName());
    }
  }
}
