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

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Rejects requests from a page that resolved its own domain to this machine (DNS rebinding): a
 * foreign {@code Host} on a server bound to loopback, and a foreign {@code Origin} on a request
 * that arrived over loopback. Listed origins and their hosts pass, and {@code *} turns both checks
 * off.
 */
final class OriginGuardFilter extends OncePerRequestFilter {

  private final boolean allowAll;
  private final ImmutableSet<String> origins;
  private final ImmutableSet<String> hosts;
  private final boolean boundToLoopback;

  OriginGuardFilter(List<String> allowedOrigins, String bindAddress) {
    this.allowAll = allowedOrigins.contains("*");
    this.origins =
        allowedOrigins.stream()
            .map(OriginGuardFilter::normalizeOrigin)
            .collect(ImmutableSet.toImmutableSet());
    this.hosts =
        origins.stream()
            .map(OriginGuardFilter::originHost)
            .filter(host -> !host.isEmpty())
            .collect(ImmutableSet.toImmutableSet());
    this.boundToLoopback = isLoopback(bindAddress);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (allowAll) {
      chain.doFilter(request, response);
      return;
    }
    // ForwardedHeaderFilter's wrapper takes the local address from headers a page can set.
    HttpServletRequest connection = unwrap(request);
    if (boundToLoopback && isForeignHost(connection)) {
      reject(response, "host not allowed");
      return;
    }
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    if (origin != null
        && !origins.contains(normalizeOrigin(origin))
        && isLoopback(connection.getLocalAddr())
        && !isLoopback(originHost(origin))) {
      // A rebound page's own domain fills Host and Origin alike, so same-origin checks pass it.
      reject(response, "origin not allowed");
      return;
    }
    chain.doFilter(request, response);
  }

  /** Whether the real Host, which a page cannot set, names neither loopback nor a listed host. */
  private boolean isForeignHost(HttpServletRequest connection) {
    // HTTP/2 sends the authority as the server name rather than a Host header.
    String host = connection.getHeader(HttpHeaders.HOST);
    if (host == null) {
      host = connection.getServerName();
    }
    return !isLoopback(host) && !hosts.contains(bareHost(host));
  }

  private static HttpServletRequest unwrap(HttpServletRequest request) {
    ServletRequest current = request;
    while (current instanceof ServletRequestWrapper wrapper) {
      current = wrapper.getRequest();
    }
    return current instanceof HttpServletRequest connection ? connection : request;
  }

  private static void reject(HttpServletResponse response, String reason) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("text/plain");
    response.getWriter().write("Forbidden: " + reason);
  }

  /** Whether host, with or without a port, is localhost or a loopback IP; never queries DNS. */
  private static boolean isLoopback(@Nullable String host) {
    String bare = bareHost(host);
    return bare.equals("localhost")
        || (InetAddresses.isInetAddress(bare) && InetAddresses.forString(bare).isLoopbackAddress());
  }

  /** Lowercases host and strips its port, brackets and trailing dot; empty if unparsable. */
  private static String bareHost(@Nullable String host) {
    if (host == null) {
      return "";
    }
    try {
      String bare = HostAndPort.fromString(host.trim()).getHost();
      return Ascii.toLowerCase(CharMatcher.is('.').trimTrailingFrom(bare));
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String originHost(String origin) {
    try {
      return bareHost(UriComponentsBuilder.fromUriString(origin.trim()).build().getHost());
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String normalizeOrigin(String origin) {
    return Ascii.toLowerCase(CharMatcher.is('/').trimTrailingFrom(origin.trim()));
  }
}
