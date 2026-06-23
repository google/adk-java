package com.google.adk.tools.mcp;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * The default builder for creating MCP client transports. Supports StdioClientTransport based on
 * {@link ServerParameters}, HttpClientSseClientTransport based on {@link SseServerParameters}, and
 * HttpClientStreamableHttpTransport based on {@link StreamableHttpServerParameters}.
 */
public class DefaultMcpTransportBuilder implements McpTransportBuilder {

  private static final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

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
      // Split the URL so the transport's URI.resolve does not drop a custom path (b/513186321).
      SplitUri split = splitBaseAndEndpoint(streamableParams.url());
      HttpClientStreamableHttpTransport.Builder builder =
          HttpClientStreamableHttpTransport.builder(split.baseUri())
              .connectTimeout(streamableParams.timeout())
              .jsonMapper(jsonMapper)
              .asyncHttpRequestCustomizer(
                  (requestBuilder, method, uri, body, context) -> {
                    streamableParams
                        .headers()
                        .forEach((key, value) -> requestBuilder.header(key, value));
                    return Mono.just(requestBuilder);
                  });
      if (split.endpoint() != null) {
        builder.endpoint(split.endpoint());
      }
      return builder.build();
    } else {
      throw new IllegalArgumentException(
          "DefaultMcpTransportBuilder supports only ServerParameters, SseServerParameters, or"
              + " StreamableHttpServerParameters, but got "
              + connectionParams.getClass().getName());
    }
  }

  /**
   * Splits the URL into a base URI (scheme + authority) and endpoint (path + query + fragment).
   * Returns a null endpoint when the URL has no meaningful path or cannot be split, so the
   * transport falls back to its default endpoint.
   */
  private static SplitUri splitBaseAndEndpoint(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return new SplitUri(url, null);
    }
    if (uri.getScheme() == null || uri.getAuthority() == null) {
      return new SplitUri(url, null);
    }
    String path = uri.getRawPath();
    if (isNullOrEmpty(path) || path.equals("/")) {
      return new SplitUri(url, null);
    }
    String baseUri = uri.getScheme() + "://" + uri.getAuthority();
    StringBuilder endpoint = new StringBuilder(path);
    if (uri.getRawQuery() != null) {
      endpoint.append('?').append(uri.getRawQuery());
    }
    if (uri.getRawFragment() != null) {
      endpoint.append('#').append(uri.getRawFragment());
    }
    return new SplitUri(baseUri, endpoint.toString());
  }

  private record SplitUri(String baseUri, String endpoint) {}
}
