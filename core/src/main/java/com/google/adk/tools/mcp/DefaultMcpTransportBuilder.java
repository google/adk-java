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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.annotations.VisibleForTesting;
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
 *
 * <p>For stdio servers, the command is resolved against {@code PATH} first on Windows, so npm shims
 * such as {@code npx} reach {@code ProcessBuilder} as the {@code .cmd} file they really are. See
 * {@link WindowsExecutableResolver}.
 */
public class DefaultMcpTransportBuilder implements McpTransportBuilder {

  private static final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

  @Override
  public McpClientTransport build(Object connectionParams) {
    if (connectionParams instanceof ServerParameters serverParameters) {
      return new StdioClientTransport(resolveCommand(serverParameters), jsonMapper);
    } else if (connectionParams instanceof SseServerParameters sseServerParams) {
      return HttpClientSseClientTransport.builder(sseServerParams.url())
          .sseEndpoint(
              sseServerParams.sseEndpoint() == null ? "sse" : sseServerParams.sseEndpoint())
          .httpRequestCustomizer(
              (builder, method, uri, body, context) ->
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
      // Split the URL so the transport's URI.resolve does not drop a custom path.
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
   * Resolves a Windows command shim before the stdio transport hands the command to {@code
   * ProcessBuilder}.
   *
   * <p>Without this, {@code command("npx")} never starts a process on Windows: the transport's
   * error is swallowed by the initialization timeout, so the toolset appears to hang. See {@link
   * WindowsExecutableResolver} and google/adk-java#1686.
   *
   * <p>Everywhere except Windows, and for commands that are already paths, the parameters are
   * returned untouched.
   */
  private static ServerParameters resolveCommand(ServerParameters serverParameters) {
    String command = serverParameters.getCommand();
    String resolved = WindowsExecutableResolver.resolve(command);
    if (resolved.equals(command)) {
      return serverParameters;
    }
    return withCommand(serverParameters, resolved);
  }

  /**
   * Returns {@code serverParameters} with {@code command} substituted, keeping the caller's args
   * and environment.
   *
   * <p>{@code ServerParameters} merges the default environment with the caller's, so round-tripping
   * {@code getEnv()} reproduces the same effective environment.
   */
  @VisibleForTesting
  static ServerParameters withCommand(ServerParameters serverParameters, String command) {
    return ServerParameters.builder(command)
        .args(serverParameters.getArgs())
        .env(serverParameters.getEnv())
        .build();
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
