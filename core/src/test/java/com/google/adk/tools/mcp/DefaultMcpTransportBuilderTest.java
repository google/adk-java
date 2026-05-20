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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.lang.reflect.Field;
import java.net.URI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DefaultMcpTransportBuilder}. */
@RunWith(JUnit4.class)
public final class DefaultMcpTransportBuilderTest {

  private final DefaultMcpTransportBuilder builder = new DefaultMcpTransportBuilder();

  // -------------------------------------------------------------------------
  // Helper: read private fields via reflection
  // -------------------------------------------------------------------------

  private static String getEndpointField(HttpClientStreamableHttpTransport transport)
      throws Exception {
    Field field = HttpClientStreamableHttpTransport.class.getDeclaredField("endpoint");
    field.setAccessible(true);
    return (String) field.get(transport);
  }

  private static URI getBaseUriField(HttpClientStreamableHttpTransport transport) throws Exception {
    Field field = HttpClientStreamableHttpTransport.class.getDeclaredField("baseUri");
    field.setAccessible(true);
    return (URI) field.get(transport);
  }

  // -------------------------------------------------------------------------
  // StreamableHttp transport tests
  // -------------------------------------------------------------------------

  @Test
  public void build_withStreamableHttpParamsWithoutEndpoint_usesLibraryDefaultEndpoint()
      throws Exception {
    // When the user does NOT set .endpoint(), the library default "/mcp" must be preserved.
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            // No .endpoint() call → endpoint() returns null
            .build();

    McpClientTransport transport = builder.build(params);

    assertThat(transport).isInstanceOf(HttpClientStreamableHttpTransport.class);
    HttpClientStreamableHttpTransport streamableTransport =
        (HttpClientStreamableHttpTransport) transport;

    // baseUri stores exactly the url passed by the user
    assertThat(getBaseUriField(streamableTransport))
        .isEqualTo(URI.create("http://localhost:8080"));
    // endpoint is the library's hard-coded default because the user did not override it
    assertThat(getEndpointField(streamableTransport)).isEqualTo("/mcp");
  }

  @Test
  public void build_withStreamableHttpParamsWithCustomEndpoint_setsEndpointOnTransport()
      throws Exception {
    // When the user sets .endpoint("/mcp/stream"), the transport's endpoint field must reflect it.
    // This is the core of the bug fix: the library's Utils.resolveUri(baseUri, endpoint) will now
    // compute URI.create("http://localhost:8080").resolve("/mcp/stream")
    //   = "http://localhost:8080/mcp/stream"  
    // instead of the broken pre-fix behaviour:
    //   URI.create("http://localhost:8080/mcp/stream").resolve("/mcp")
    //   = "http://localhost:8080/mcp"          
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .endpoint("/mcp/stream")
            .build();

    McpClientTransport transport = builder.build(params);

    assertThat(transport).isInstanceOf(HttpClientStreamableHttpTransport.class);
    HttpClientStreamableHttpTransport streamableTransport =
        (HttpClientStreamableHttpTransport) transport;

    assertThat(getBaseUriField(streamableTransport))
        .isEqualTo(URI.create("http://localhost:8080"));
    // endpoint was explicitly overridden — must NOT be the default "/mcp"
    assertThat(getEndpointField(streamableTransport)).isEqualTo("/mcp/stream");
  }

  @Test
  public void build_withStreamableHttpParams_defaultEndpointProducesCorrectFinalUri()
      throws Exception {
    // Confirm that the final resolved URI for default case is http://host/mcp (not broken).
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder().url("http://localhost:8080").build();

    McpClientTransport transport = builder.build(params);
    HttpClientStreamableHttpTransport streamableTransport =
        (HttpClientStreamableHttpTransport) transport;

    URI base = getBaseUriField(streamableTransport);
    String endpoint = getEndpointField(streamableTransport);
    URI resolved = base.resolve(endpoint);

    assertThat(resolved).isEqualTo(URI.create("http://localhost:8080/mcp"));
  }

  @Test
  public void build_withStreamableHttpParams_customEndpointProducesCorrectFinalUri()
      throws Exception {
    // Confirm that the final resolved URI for the custom endpoint case is correct.
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .endpoint("/mcp/stream")
            .build();

    McpClientTransport transport = builder.build(params);
    HttpClientStreamableHttpTransport streamableTransport =
        (HttpClientStreamableHttpTransport) transport;

    URI base = getBaseUriField(streamableTransport);
    String endpoint = getEndpointField(streamableTransport);
    URI resolved = base.resolve(endpoint);

    assertThat(resolved).isEqualTo(URI.create("http://localhost:8080/mcp/stream"));
  }

  // -------------------------------------------------------------------------
  // SSE transport tests — ensure existing behaviour is unchanged
  // -------------------------------------------------------------------------

  @Test
  public void build_withSseParams_returnsSseTransport() {
    SseServerParameters params =
        SseServerParameters.builder().url("http://localhost:8080").build();

    McpClientTransport transport = builder.build(params);

    assertThat(transport).isInstanceOf(HttpClientSseClientTransport.class);
  }

  @Test
  public void build_withSseParamsWithCustomSseEndpoint_returnsSseTransport() {
    SseServerParameters params =
        SseServerParameters.builder()
            .url("http://localhost:8080")
            .sseEndpoint("events")
            .build();

    McpClientTransport transport = builder.build(params);

    assertThat(transport).isInstanceOf(HttpClientSseClientTransport.class);
  }

  // -------------------------------------------------------------------------
  // Stdio transport tests — ensure existing behaviour is unchanged
  // -------------------------------------------------------------------------

  @Test
  public void build_withStdioParams_returnsStdioTransport() {
    ServerParameters params = ServerParameters.builder("echo").args("hello").build();

    McpClientTransport transport = builder.build(params);

    assertThat(transport).isInstanceOf(StdioClientTransport.class);
  }

  // -------------------------------------------------------------------------
  // Unknown param type test
  // -------------------------------------------------------------------------

  @Test
  public void build_withUnknownParamType_throwsIllegalArgumentException() {
    Object unknownParams = new Object();

    assertThrows(IllegalArgumentException.class, () -> builder.build(unknownParams));
  }
}
