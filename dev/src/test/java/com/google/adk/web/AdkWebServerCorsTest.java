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

package com.google.adk.web;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tests for the browser origins allowed to call the REST API and open the {@code /run_live}
 * WebSocket.
 */
final class AdkWebServerCorsTest {

  private static final String REMOTE_ORIGIN = "https://evil.example";
  private static final HttpClient httpClient = HttpClient.newHttpClient();

  @Nested
  @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
  @AutoConfigureMockMvc
  class DefaultOrigins {

    @ParameterizedTest
    @ValueSource(strings = {REMOTE_ORIGIN, "http://localhost:4200"})
    public void restApi_fromOtherOrigin_isRejected(String origin, @Autowired MockMvc mockMvc)
        throws Exception {
      // The DNS-rebinding guard does not apply to requests over the network, so CORS decides.
      mockMvc
          .perform(get("/list-apps").header(ORIGIN, origin).with(overNetwork()))
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void restApi_withMalformedOrigin_isRejected(@Autowired MockMvc mockMvc)
        throws Exception {
      mockMvc
          .perform(get("/list-apps").header(ORIGIN, "http://a b").with(overNetwork()))
          .andExpect(status().isForbidden());
    }

    @Test
    public void restApi_fromSameOrigin_isAllowed(@Autowired MockMvc mockMvc) throws Exception {
      // Same-origin requests skip the origin list, so any host name on the network works.
      mockMvc
          .perform(
              get("http://devbox:8000/list-apps")
                  .header(ORIGIN, "http://devbox:8000")
                  .with(overNetwork()))
          .andExpect(status().isOk());
    }

    @Test
    public void restApi_behindTlsProxy_isAllowed(@Autowired MockMvc mockMvc) throws Exception {
      // The proxy forwards plain HTTP; its X-Forwarded-Proto restores the browser's https origin.
      mockMvc
          .perform(
              get("http://app.example.com/list-apps")
                  .header("X-Forwarded-Proto", "https")
                  .header(ORIGIN, "https://app.example.com")
                  .with(overNetwork()))
          .andExpect(status().isOk());
    }

    @Test
    public void restApi_behindTlsProxy_fromRemoteOrigin_isRejected(@Autowired MockMvc mockMvc)
        throws Exception {
      mockMvc
          .perform(
              get("http://app.example.com/list-apps")
                  .header("X-Forwarded-Proto", "https")
                  .header(ORIGIN, REMOTE_ORIGIN)
                  .with(overNetwork()))
          .andExpect(status().isForbidden());
    }

    @Test
    public void preflight_fromRemoteOrigin_isRejected(@Autowired MockMvc mockMvc) throws Exception {
      mockMvc
          .perform(
              options("/apps/test-app/users/test-user/sessions/test-session")
                  .header(ORIGIN, REMOTE_ORIGIN)
                  .header(ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                  .with(overNetwork()))
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void runLive_fromSameOrigin_completesHandshake(@LocalServerPort int port) {
      openRunLive(port, "http://localhost:" + port).abort();
    }

    @ParameterizedTest
    @ValueSource(strings = {REMOTE_ORIGIN, "http://localhost:4200"})
    public void runLive_fromOtherOrigin_isRejected(String origin, @LocalServerPort int port) {
      assertHandshakeForbidden(port, origin);
    }

    @Test
    public void runLive_behindTlsProxy_completesHandshake(@LocalServerPort int port) {
      // A same-machine TLS proxy at https://127.0.0.1; only forwarded headers make this match.
      WebSocket.Builder handshake =
          httpClient
              .newWebSocketBuilder()
              .header(ORIGIN, "https://127.0.0.1")
              .header("X-Forwarded-Proto", "https")
              .header("X-Forwarded-Host", "127.0.0.1");

      connect(port, handshake).abort();
    }

    @Test
    public void runLive_reboundOriginBehindHostRewritingProxy_isRejected(
        @LocalServerPort int port) {
      // A rebound page reaching a same-machine proxy that forwards the page's own host.
      WebSocket.Builder handshake =
          httpClient
              .newWebSocketBuilder()
              .header(ORIGIN, REMOTE_ORIGIN)
              .header("X-Forwarded-Proto", "https")
              .header("X-Forwarded-Host", "evil.example");

      assertHandshakeForbidden(port, handshake);
    }
  }

  /** With the CORS filter mapped away from /run_live, the handshake's own origin check decides. */
  @Nested
  @SpringBootTest(
      webEnvironment = WebEnvironment.RANDOM_PORT,
      properties = "adk.web.cors.mapping=/apps/**")
  class CorsFilterNotOnRunLive {

    @Test
    public void runLive_fromSameOrigin_completesHandshake(@LocalServerPort int port) {
      openRunLive(port, "http://localhost:" + port).abort();
    }

    @Test
    public void runLive_fromOtherLocalPort_isRejected(@LocalServerPort int port) {
      // A loopback origin passes the DNS-rebinding guard, so only the handshake check refuses it.
      assertHandshakeForbidden(port, "http://localhost:4200");
    }
  }

  @Nested
  @SpringBootTest(
      webEnvironment = WebEnvironment.RANDOM_PORT,
      properties = "adk.web.cors.origins=https://ui.example.com")
  @AutoConfigureMockMvc
  class ConfiguredOrigins {

    @Test
    public void restApi_fromConfiguredOrigin_isAllowed(@Autowired MockMvc mockMvc)
        throws Exception {
      mockMvc
          .perform(get("/list-apps").header(ORIGIN, "https://ui.example.com"))
          .andExpect(status().isOk())
          .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "https://ui.example.com"));
    }

    @Test
    public void restApi_behindTlsProxy_isAllowed(@Autowired MockMvc mockMvc) throws Exception {
      mockMvc
          .perform(
              get("http://app.example.com/list-apps")
                  .header("X-Forwarded-Proto", "https")
                  .header(ORIGIN, "https://app.example.com")
                  .with(overNetwork()))
          .andExpect(status().isOk());
    }

    @Test
    public void runLive_fromConfiguredOrigin_completesHandshake(@LocalServerPort int port) {
      openRunLive(port, "https://ui.example.com").abort();
    }

    @Test
    public void runLive_fromUnlistedOrigin_isRejected(@LocalServerPort int port) {
      // A loopback origin passes the DNS-rebinding guard, so the configured list must refuse it.
      assertHandshakeForbidden(port, "http://localhost:4200");
    }
  }

  @Nested
  @SpringBootTest(
      webEnvironment = WebEnvironment.RANDOM_PORT,
      properties = "adk.web.cors.origins=*")
  @AutoConfigureMockMvc
  class AllOriginsOptIn {

    @Test
    public void restApi_fromRemoteOrigin_isAllowed(@Autowired MockMvc mockMvc) throws Exception {
      mockMvc
          .perform(get("/list-apps").header(ORIGIN, REMOTE_ORIGIN))
          .andExpect(status().isOk())
          .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "*"));
    }

    @Test
    public void runLive_fromRemoteOrigin_completesHandshake(@LocalServerPort int port) {
      openRunLive(port, REMOTE_ORIGIN).abort();
    }
  }

  /** Delivers a MockMvc request on a routable address, as a LAN host or remote proxy does. */
  private static RequestPostProcessor overNetwork() {
    return request -> {
      request.setLocalAddr("10.0.0.5");
      return request;
    };
  }

  private static WebSocket openRunLive(int port, String origin) {
    return connect(port, httpClient.newWebSocketBuilder().header(ORIGIN, origin));
  }

  private static WebSocket connect(int port, WebSocket.Builder handshake) {
    return handshake
        .buildAsync(URI.create("ws://localhost:" + port + "/run_live"), new WebSocket.Listener() {})
        .join();
  }

  private static void assertHandshakeForbidden(int port, String origin) {
    assertHandshakeForbidden(port, httpClient.newWebSocketBuilder().header(ORIGIN, origin));
  }

  private static void assertHandshakeForbidden(int port, WebSocket.Builder handshake) {
    CompletionException thrown =
        assertThrows(CompletionException.class, () -> connect(port, handshake));

    assertThat(thrown).hasCauseThat().isInstanceOf(WebSocketHandshakeException.class);
    WebSocketHandshakeException cause = (WebSocketHandshakeException) thrown.getCause();
    assertThat(cause.getResponse().statusCode()).isEqualTo(403);
  }
}
