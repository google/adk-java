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

package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the request paths built by {@link VertexAiClient}.
 *
 * <p>Session ids reach the client from user-supplied {@code Session} objects, so they must be
 * escaped before being concatenated into a URL path. Otherwise a session id containing {@code /} or
 * {@code ..} can retarget the request at a different resource, and one containing {@code ?} can
 * append arbitrary query parameters.
 */
@RunWith(JUnit4.class)
public final class VertexAiClientTest {

  private static final MediaType JSON_MEDIA_TYPE =
      MediaType.parse("application/json; charset=utf-8");

  @Mock private HttpApiClient mockApiClient;

  private VertexAiClient client;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    client = new VertexAiClient("test-project", "test-location", mockApiClient);
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody("{}"));
  }

  private static ApiResponse responseWithBody(String body) {
    return new ApiResponse() {
      @Override
      public ResponseBody getResponseBody() {
        return ResponseBody.create(JSON_MEDIA_TYPE, body);
      }

      @Override
      public void close() {}
    };
  }

  /** Returns the path passed to the single expected {@code HttpApiClient.request} call. */
  private String capturePath(String expectedMethod) {
    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(eq(expectedMethod), pathCaptor.capture(), anyString());
    return pathCaptor.getValue();
  }

  @Test
  public void getSession_normalSessionId_isNotEscaped() {
    client.getSession("123", "456").blockingGet();

    assertThat(capturePath("GET")).isEqualTo("reasoningEngines/123/sessions/456");
  }

  @Test
  public void getSession_sessionIdWithPathTraversal_isEscaped() {
    client.getSession("123", "../../secret").blockingGet();

    String path = capturePath("GET");
    assertThat(path).isEqualTo("reasoningEngines/123/sessions/..%2F..%2Fsecret");
    assertThat(path).doesNotContain("../");
  }

  @Test
  public void getSession_sessionIdStartingQueryString_isEscaped() {
    client.getSession("123", "456?view=FULL").blockingGet();

    String path = capturePath("GET");
    assertThat(path).isEqualTo("reasoningEngines/123/sessions/456%3Fview=FULL");
    assertThat(path).doesNotContain("?");
  }

  @Test
  public void deleteSession_sessionIdWithPathTraversal_isEscaped() {
    client.deleteSession("123", "../456").blockingAwait();

    assertThat(capturePath("DELETE")).isEqualTo("reasoningEngines/123/sessions/..%2F456");
  }

  @Test
  public void appendEvent_normalSessionId_keepsAppendEventVerb() {
    client.appendEvent("123", "456", "{}").blockingAwait();

    assertThat(capturePath("POST")).isEqualTo("reasoningEngines/123/sessions/456:appendEvent");
  }

  @Test
  public void appendEvent_sessionIdWithPathTraversal_isEscaped() {
    client.appendEvent("123", "../456", "{}").blockingAwait();

    String path = capturePath("POST");
    assertThat(path).isEqualTo("reasoningEngines/123/sessions/..%2F456:appendEvent");
    assertThat(path).doesNotContain("../");
  }

  @Test
  public void listEvents_normalSessionIdWithoutFilter_isNotEscaped() {
    client.listEvents("123", "456", null).blockingGet();

    assertThat(capturePath("GET")).isEqualTo("reasoningEngines/123/sessions/456/events");
  }

  @Test
  public void listEvents_sessionIdWithPathTraversal_isEscaped() {
    client.listEvents("123", "../../other/sessions/target", null).blockingGet();

    String path = capturePath("GET");
    assertThat(path)
        .isEqualTo("reasoningEngines/123/sessions/..%2F..%2Fother%2Fsessions%2Ftarget/events");
    assertThat(path).doesNotContain("../");
  }

  @Test
  public void listEvents_sessionIdWithFilter_escapesSessionIdAndKeepsFilter() {
    client.listEvents("123", "4/5", "timestamp>=\"2024-12-12T12:12:12Z\"").blockingGet();

    String path = capturePath("GET");
    assertThat(path).startsWith("reasoningEngines/123/sessions/4%2F5/events?filter=");
    String filter = path.substring(path.indexOf("?filter=") + "?filter=".length());
    assertThat(URLDecoder.decode(filter, StandardCharsets.UTF_8))
        .isEqualTo("timestamp>=\"2024-12-12T12:12:12Z\"");
  }

  @Test
  public void listSessions_normalUserId_isSentAsQuotedFilterLiteral() {
    client.listSessions("123", "user").blockingGet();

    String path = capturePath("GET");
    assertThat(path).startsWith("reasoningEngines/123/sessions?filter=");
    assertThat(decodeFilter(path)).isEqualTo("user_id=\"user\"");
  }

  @Test
  public void listSessions_userIdWithFilterInjection_isQuotedAndEscaped() {
    client.listSessions("123", "user\" OR user_id=\"admin").blockingGet();

    String path = capturePath("GET");
    // The injected quotes stay inside the literal instead of terminating it, so the
    // server still sees a single user_id term.
    assertThat(decodeFilter(path)).isEqualTo("user_id=\"user\\\" OR user_id=\\\"admin\"");
  }

  @Test
  public void listSessions_userIdWithQueryParamInjection_isEscaped() {
    client.listSessions("123", "user&pageSize=1000").blockingGet();

    String path = capturePath("GET");
    assertThat(path).doesNotContain("&pageSize=1000");
    assertThat(decodeFilter(path)).isEqualTo("user_id=\"user&pageSize=1000\"");
  }

  private static String decodeFilter(String path) {
    return URLDecoder.decode(
        path.substring(path.indexOf("?filter=") + "?filter=".length()), StandardCharsets.UTF_8);
  }
}
