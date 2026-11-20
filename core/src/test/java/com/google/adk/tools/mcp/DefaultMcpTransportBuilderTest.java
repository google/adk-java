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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import reactor.core.publisher.Mono;

/** Unit tests for {@link DefaultMcpTransportBuilder}. */
@RunWith(JUnit4.class)
public final class DefaultMcpTransportBuilderTest {

  private final DefaultMcpTransportBuilder transportBuilder = new DefaultMcpTransportBuilder();

  @Test
  public void build_withServerParameters_returnsStdioTransport() {
    ServerParameters params = ServerParameters.builder("test-command").build();

    McpClientTransport transport = transportBuilder.build(params);

    assertThat(transport).isInstanceOf(StdioClientTransport.class);
  }

  @Test
  public void build_withServerParameters_preservesArgsAndEnv() throws Exception {
    // Command resolution rebuilds ServerParameters; that rebuild must not drop the caller's args or
    // environment, or the stdio server would start with the wrong argv.
    ServerParameters params =
        ServerParameters.builder("test-command")
            .args("-y", "@modelcontextprotocol/server-filesystem", "/tmp")
            .addEnvVar("CUSTOM_VAR", "custom-value")
            .build();

    StdioClientTransport transport = (StdioClientTransport) transportBuilder.build(params);
    ServerParameters built = getServerParameters(transport);

    assertThat(built.getArgs())
        .containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .inOrder();
    assertThat(built.getEnv()).containsEntry("CUSTOM_VAR", "custom-value");
  }

  @Test
  public void withCommand_replacesCommandAndKeepsArgsAndEnv() {
    // Deterministic on every platform: exercises the rebuild that resolveCommand performs on
    // Windows, which the builder test below cannot reach on a non-Windows CI machine.
    ServerParameters params =
        ServerParameters.builder("npx")
            .args("-y", "@modelcontextprotocol/server-filesystem", "/tmp")
            .addEnvVar("CUSTOM_VAR", "custom-value")
            .build();

    ServerParameters rebuilt =
        DefaultMcpTransportBuilder.withCommand(params, "C:\\nodejs\\npx.cmd");

    assertThat(rebuilt.getCommand()).isEqualTo("C:\\nodejs\\npx.cmd");
    assertThat(rebuilt.getArgs())
        .containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .inOrder();
    assertThat(rebuilt.getEnv()).containsEntry("CUSTOM_VAR", "custom-value");
  }

  @Test
  public void build_withServerParameters_appliesCommandResolution() throws Exception {
    // Regression test for google/adk-java#1686: the builder must hand the transport whatever the
    // resolver produced, so a Windows npm shim reaches ProcessBuilder as a real file.
    ServerParameters params = ServerParameters.builder("npx").build();

    StdioClientTransport transport = (StdioClientTransport) transportBuilder.build(params);

    assertThat(getServerParameters(transport).getCommand())
        .isEqualTo(WindowsExecutableResolver.resolve("npx"));
  }

  @Test
  public void build_withServerParameters_onWindows_resolvesNpxShimToCmdFile() throws Exception {
    assumeTrue(
        "Windows-only: asserts that the npm shim is actually found on this machine",
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    assumeTrue("Needs Node.js on PATH", WindowsExecutableResolver.resolve("npx").endsWith(".cmd"));

    ServerParameters params = ServerParameters.builder("npx").build();

    StdioClientTransport transport = (StdioClientTransport) transportBuilder.build(params);

    String command = getServerParameters(transport).getCommand();
    assertThat(command.toLowerCase(Locale.ROOT)).endsWith("npx.cmd");
    assertThat(new File(command).isFile()).isTrue();
  }

  @Test
  public void build_withSseServerParameters_returnsSseTransport() {
    SseServerParameters params = SseServerParameters.builder().url("http://localhost:1234").build();

    McpClientTransport transport = transportBuilder.build(params);

    assertThat(transport).isInstanceOf(HttpClientSseClientTransport.class);
  }

  @Test
  public void build_withStreamableHttpServerParameters_returnsStreamableHttpTransport() {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("http://localhost:1234").build();

    McpClientTransport transport = transportBuilder.build(params);

    assertThat(transport).isInstanceOf(HttpClientStreamableHttpTransport.class);
  }

  @Test
  public void build_withUnknownConnectionParams_throwsIllegalArgumentException() {
    Object unknownParams = new Object();

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> transportBuilder.build(unknownParams));

    assertThat(ex).hasMessageThat().contains("DefaultMcpTransportBuilder supports only");
  }

  @Test
  public void build_withStreamableHttpUrlWithoutPath_usesDefaultEndpoint() throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("http://localhost:8080").build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    assertThat(getBaseUri(transport)).isEqualTo(URI.create("http://localhost:8080"));
    assertThat(getEndpoint(transport)).isEqualTo("/mcp");
  }

  @Test
  public void build_withStreamableHttpUrlWithRootPath_usesDefaultEndpoint() throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("http://localhost:8080/").build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    assertThat(getEndpoint(transport)).isEqualTo("/mcp");
  }

  @Test
  public void build_withStreamableHttpCustomEndpointPath_preservesCustomPath() throws Exception {
    // Regression test for google/adk-java#1196.
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("http://localhost:8080/mcp/stream").build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    assertThat(getBaseUri(transport)).isEqualTo(URI.create("http://localhost:8080"));
    assertThat(getEndpoint(transport)).isEqualTo("/mcp/stream");
  }

  @Test
  public void build_withStreamableHttpCustomEndpoint_resolvesToFullUrl() throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("http://localhost:8080/mcp/stream").build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    URI resolved = getBaseUri(transport).resolve(getEndpoint(transport));
    assertThat(resolved).isEqualTo(URI.create("http://localhost:8080/mcp/stream"));
  }

  @Test
  public void build_withStreamableHttpDeepCustomPath_preservesEntirePath() throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("https://example.com/api/v1/mcp/stream")
            .build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    assertThat(getBaseUri(transport)).isEqualTo(URI.create("https://example.com"));
    assertThat(getEndpoint(transport)).isEqualTo("/api/v1/mcp/stream");
  }

  @Test
  public void build_withStreamableHttpQueryAndFragment_preservesQueryAndFragment()
      throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("https://example.com/mcp/stream?token=abc#frag")
            .build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    assertThat(getBaseUri(transport)).isEqualTo(URI.create("https://example.com"));
    assertThat(getEndpoint(transport)).isEqualTo("/mcp/stream?token=abc#frag");
  }

  @Test
  public void build_withStreamableHttpEncodedPath_preservesEncoding() throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("https://example.com/mcp%20stream/path")
            .build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    assertThat(getBaseUri(transport)).isEqualTo(URI.create("https://example.com"));
    assertThat(getEndpoint(transport)).isEqualTo("/mcp%20stream/path");
  }

  @Test
  public void build_withStreamableHttpHeaders_customizerForwardsHeadersToRequest()
      throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080/mcp/stream")
            .headers(ImmutableMap.of("X-Custom", "value", "Authorization", "Bearer token"))
            .build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);
    McpAsyncHttpClientRequestCustomizer customizer = getCustomizer(transport);
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create("http://x/"));

    HttpRequest.Builder returned =
        Mono.from(
                customizer.customize(
                    requestBuilder,
                    "POST",
                    URI.create("http://x/"),
                    null,
                    McpTransportContext.EMPTY))
            .block();

    assertThat(returned).isSameInstanceAs(requestBuilder);
    Map<String, String> headers = collectHeaders(requestBuilder);
    assertThat(headers).containsEntry("X-Custom", "value");
    assertThat(headers).containsEntry("Authorization", "Bearer token");
  }

  @Test
  public void build_withStreamableHttpEmptyHeaders_customizerIsNoOp() throws Exception {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080/mcp/stream")
            .headers(ImmutableMap.of())
            .build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);
    McpAsyncHttpClientRequestCustomizer customizer = getCustomizer(transport);
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create("http://x/"));

    Mono.from(
            customizer.customize(
                requestBuilder, "POST", URI.create("http://x/"), null, McpTransportContext.EMPTY))
        .block();

    assertThat(collectHeaders(requestBuilder)).isEmpty();
  }

  @Test
  public void build_withStreamableHttpMalformedUrl_doesNotMaskUnderlyingError() {
    // Unparseable URL: split helper forwards it as-is so the transport surfaces its own error.
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("http://example.com/path with space").build();

    assertThrows(IllegalArgumentException.class, () -> transportBuilder.build(params));
  }

  @Test
  public void build_withStreamableHttpSchemelessUrl_forwardsUnchangedAsBaseUri() throws Exception {
    // No scheme/authority: split helper forwards the URL as-is and keeps the default endpoint.
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("relative/path").build();

    HttpClientStreamableHttpTransport transport =
        (HttpClientStreamableHttpTransport) transportBuilder.build(params);

    assertThat(getBaseUri(transport)).isEqualTo(URI.create("relative/path"));
    assertThat(getEndpoint(transport)).isEqualTo("/mcp");
  }

  private static URI getBaseUri(HttpClientStreamableHttpTransport transport) throws Exception {
    Field field = HttpClientStreamableHttpTransport.class.getDeclaredField("baseUri");
    field.setAccessible(true);
    return (URI) field.get(transport);
  }

  /** Reads back the parameters the transport will hand to {@code ProcessBuilder}. */
  private static ServerParameters getServerParameters(StdioClientTransport transport)
      throws Exception {
    Field field = StdioClientTransport.class.getDeclaredField("params");
    field.setAccessible(true);
    return (ServerParameters) field.get(transport);
  }

  private static String getEndpoint(HttpClientStreamableHttpTransport transport) throws Exception {
    Field field = HttpClientStreamableHttpTransport.class.getDeclaredField("endpoint");
    field.setAccessible(true);
    return (String) field.get(transport);
  }

  private static McpAsyncHttpClientRequestCustomizer getCustomizer(
      HttpClientStreamableHttpTransport transport) throws Exception {
    Field field = HttpClientStreamableHttpTransport.class.getDeclaredField("httpRequestCustomizer");
    field.setAccessible(true);
    return (McpAsyncHttpClientRequestCustomizer) field.get(transport);
  }

  /** Reads back the headers set on a builder by building a throwaway request. */
  private static Map<String, String> collectHeaders(HttpRequest.Builder builder) {
    HttpRequest request = builder.GET().build();
    Map<String, String> result = new HashMap<>();
    request.headers().map().forEach((key, values) -> result.put(key, String.join(",", values)));
    return result;
  }
}
