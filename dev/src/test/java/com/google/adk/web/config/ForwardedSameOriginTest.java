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

package com.google.adk.web.config;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

final class ForwardedSameOriginTest {

  @Test
  public void matches_originOfDirectRequest_returnsTrue() {
    MockHttpServletRequest request = requestTo("app.example.com");
    request.addHeader("Origin", "http://app.example.com");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isTrue();
  }

  @Test
  public void matches_originFromXForwardedHeaders_returnsTrue() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "https://app.example.com");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "app.example.com");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isTrue();
  }

  @Test
  public void matches_originFromForwardedHeader_returnsTrue() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "https://app.example.com");
    request.addHeader("Forwarded", "proto=https;host=app.example.com");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"ws, http://app.example.com", "wss, https://app.example.com"})
  public void matches_webSocketSchemeFromProxy_returnsTrue(String scheme, String origin) {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", origin);
    request.addHeader("X-Forwarded-Proto", scheme);
    request.addHeader("X-Forwarded-Host", "app.example.com");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isTrue();
  }

  @Test
  public void matches_forwardedWithoutHost_ignoresXForwardedHost() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "https://app.example.com");
    request.addHeader("Forwarded", "proto=https");
    request.addHeader("X-Forwarded-Host", "app.example.com");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isFalse();
  }

  @Test
  public void matches_forwardedSchemeWithoutPort_assumesDefaultPort() {
    MockHttpServletRequest request = requestTo("app.example.com");
    request.setServerPort(8443);
    request.addHeader("Origin", "https://app.example.com:8443");
    request.addHeader("X-Forwarded-Proto", "https");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isFalse();
  }

  @Test
  public void matches_otherOriginBehindProxy_returnsFalse() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "https://evil.example");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "app.example.com");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isFalse();
  }

  @Test
  public void matches_noOriginHeader_returnsTrue() {
    MockHttpServletRequest request = requestTo("localhost");
    // Without an Origin there is nothing to compare, so even a malformed forwarded header passes.
    request.addHeader("X-Forwarded-Port", "not-a-port");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isTrue();
  }

  @Test
  public void matches_malformedOrigin_returnsFalse() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "http://a b");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isFalse();
  }

  @Test
  public void matches_emptyForwardedProtoList_returnsFalse() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "http://localhost");
    request.addHeader("X-Forwarded-Proto", ",");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isFalse();
  }

  @Test
  public void matches_malformedForwardedHost_returnsFalse() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "https://app.example.com");
    request.addHeader("X-Forwarded-Host", "a b");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isFalse();
  }

  @Test
  public void matches_malformedForwardedPort_returnsFalse() {
    MockHttpServletRequest request = requestTo("localhost");
    request.addHeader("Origin", "https://app.example.com");
    request.addHeader("X-Forwarded-Host", "app.example.com");
    request.addHeader("X-Forwarded-Port", "not-a-port");

    assertThat(ForwardedSameOrigin.matches(new ServletServerHttpRequest(request))).isFalse();
  }

  private static MockHttpServletRequest requestTo(String host) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/list-apps");
    request.setServerName(host);
    return request;
  }
}
