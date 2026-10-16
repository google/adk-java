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

import java.net.URI;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.util.ForwardedHeaderUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.WebUtils;

/**
 * Same-origin check against the URL that a reverse proxy reports in the {@code Forwarded} header,
 * or in {@code X-Forwarded-*} headers when {@code Forwarded} is absent. Browsers let a cross-origin
 * page send those headers only after a CORS preflight, which fails the origin check, so such a page
 * cannot fake a match. Other clients can send any value, so this is not an authorization check.
 */
public final class ForwardedSameOrigin {

  /**
   * Returns whether the {@code Origin} header matches the request URL as the proxy reports it. A
   * request without an {@code Origin} header matches; one with a header Spring cannot parse does
   * not.
   */
  public static boolean matches(HttpRequest request) {
    if (request.getHeaders().getOrigin() == null) {
      return true;
    }
    try {
      UriComponentsBuilder forwarded =
          ForwardedHeaderUtils.adaptFromForwardedHeaders(request.getURI(), request.getHeaders());
      // Some proxies report a WebSocket handshake as ws or wss; the browser's Origin uses http(s).
      String scheme = forwarded.build().getScheme();
      if ("ws".equalsIgnoreCase(scheme)) {
        forwarded.scheme("http");
      } else if ("wss".equalsIgnoreCase(scheme)) {
        forwarded.scheme("https");
      }
      URI forwardedUri = forwarded.build().toUri();
      return WebUtils.isSameOrigin(
          new HttpRequestWrapper(request) {
            @Override
            public URI getURI() {
              return forwardedUri;
            }
          });
    } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
      // Spring's parsers throw these for a malformed Origin, forwarded header or request URL.
      return false;
    }
  }

  private ForwardedSameOrigin() {}
}
