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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The address browsers reach this server on, from {@code adk.web.backend-url}. Parsed in one place,
 * so the dev UI's config and its entry redirect read the same setting the same way.
 */
public final class BackendUrl {

  private static final Logger log = LoggerFactory.getLogger(BackendUrl.class);

  /** The UI strips the scheme case-sensitively, so an upper-case one is not usable. */
  private static final Pattern ABSOLUTE_URL = Pattern.compile("^https?://.+");

  private static final BackendUrl UNSET = new BackendUrl("", "");

  private final String value;
  private final String pathPrefix;

  private BackendUrl(String value, String pathPrefix) {
    this.value = value;
    this.pathPrefix = pathPrefix;
  }

  /** Interprets {@code configured}, warning once if it is not something the UI can use. */
  public static BackendUrl from(@Nullable String configured) {
    if (configured == null || configured.trim().isEmpty()) {
      return UNSET;
    }
    String trimmed = configured.trim();
    // A trailing slash would double up: the UI appends paths that already start with one.
    String normalized = CharMatcher.is('/').trimTrailingFrom(trimmed);
    String path = pathOf(normalized);
    if (ABSOLUTE_URL.matcher(normalized).matches() && path != null) {
      return new BackendUrl(normalized, path);
    }
    log.warn(
        "adk.web.backend-url should be an absolute URL, but is \"{}\". The dev UI reads a value"
            + " without a lower-case http:// or https:// scheme as the host of its live/websocket"
            + " connection.",
        trimmed);
    // Served as configured: an explicit value is never silently discarded.
    return new BackendUrl(trimmed, path == null ? "" : path);
  }

  /** What the dev UI's runtime config reports, or empty when unset. */
  public String value() {
    return value;
  }

  /**
   * The path a gateway strips, which the entry redirect has to carry, or empty when there is none.
   * Percent-encoding is kept, because this goes into a {@code Location} header.
   */
  public String pathPrefix() {
    return pathPrefix;
  }

  /**
   * The URL's path, or null when it is absent, relative, or carries something the UI cannot use.
   */
  private static @Nullable String pathOf(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return null;
    }
    // The UI appends onto the whole value, so a query, fragment or userinfo would end up spliced
    // into the middle of every request it builds.
    if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
      return null;
    }
    String raw = uri.getRawPath();
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    if (!raw.startsWith("/")) {
      return null;
    }
    // "//host" in a Location is protocol-relative, so a browser would read it as a host.
    return CharMatcher.is('/').trimTrailingFrom(raw.replaceAll("^/+", "/"));
  }
}
