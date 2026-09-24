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

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import java.net.URI;
import java.net.URISyntaxException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsed representation of {@code adk.web.backend-url}, shared by the runtime-config endpoint and
 * the dev UI entry redirects.
 */
public final class BackendUrl {

  private static final Logger log = LoggerFactory.getLogger(BackendUrl.class);

  private static final BackendUrl UNSET = new BackendUrl("", "");

  private final String value;
  private final String pathPrefix;

  private BackendUrl(String value, String pathPrefix) {
    this.value = value;
    this.pathPrefix = pathPrefix;
  }

  /**
   * Parses {@code configured}. A URL the dev UI cannot use is ignored entirely, with a warning: it
   * is neither served nor used as a prefix, so the bundled {@code backendUrl} stands. Serving it
   * would publish whatever it contains, credentials included, to every browser.
   */
  public static BackendUrl from(@Nullable String configured) {
    String trimmed = Strings.nullToEmpty(configured).trim();
    if (trimmed.isEmpty()) {
      return UNSET;
    }
    // Strip trailing slashes because the UI appends paths that already start with "/".
    String normalized = CharMatcher.is('/').trimTrailingFrom(trimmed);
    URI uri = parse(normalized);
    String fault = faultIn(uri);
    if (fault == null) {
      return new BackendUrl(normalized, pathPrefixOf(uri));
    }
    // The value is not echoed: it can carry credentials, and the operator set it themselves.
    log.warn("adk.web.backend-url is ignored because {}.", fault);
    return UNSET;
  }

  /** Returns the URL to report in {@code runtime-config.json}, or empty when unset or ignored. */
  public String value() {
    return value;
  }

  /**
   * Returns the URL path prefix to prepend to the entry redirect, or empty if unset or ignored.
   * Preserves raw percent-encoding for use in a {@code Location} header.
   */
  public String pathPrefix() {
    return pathPrefix;
  }

  private static @Nullable URI parse(String url) {
    try {
      return new URI(url);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static @Nullable String faultIn(@Nullable URI uri) {
    if (uri == null) {
      return "it is not a valid URI";
    }
    // The UI strips "http://" or "https://" case-sensitively when building its WebSocket URL.
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
      return "the scheme must be a lower-case http or https";
    }
    if (uri.getHost() == null) {
      return "it names no host";
    }
    if (uri.getRawUserInfo() != null) {
      return "it carries credentials, which would be served to every client";
    }
    // The UI appends request paths directly onto backendUrl, so a query or fragment would corrupt
    // the resulting URL.
    if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
      return "it carries a query or fragment";
    }
    return null;
  }

  private static String pathPrefixOf(URI uri) {
    String raw = uri.getRawPath();
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    // Collapse leading slashes so "//segment" cannot be interpreted as a protocol-relative host.
    return CharMatcher.is('/').trimTrailingFrom(raw.replaceAll("^/+", "/"));
  }
}
