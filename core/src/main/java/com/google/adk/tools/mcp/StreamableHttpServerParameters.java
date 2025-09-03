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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.modelcontextprotocol.util.Assert;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Server parameters for Streamable HTTP client transport.
 *
 * @param url The base URL for the MCP Streamable HTTP server.
 * @param headers Optional headers to include in requests.
 * @param timeout Timeout for HTTP operations (default: 30 seconds).
 * @param sseReadTimeout Timeout for reading data from the SSE stream (default: 5 minutes).
 * @param terminateOnClose Whether to terminate the session on close (default: true).
 */
public record StreamableHttpServerParameters(
    String url,
    Map<String, String> headers,
    Duration timeout,
    Duration sseReadTimeout,
    boolean terminateOnClose) {

  public StreamableHttpServerParameters {
    Assert.hasText(url, "url must not be empty");
    headers = headers == null ? Collections.emptyMap() : headers;
    timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    sseReadTimeout = sseReadTimeout == null ? Duration.ofMinutes(5) : sseReadTimeout;
  }

  public static Builder builder(String url) {
    return new Builder(url);
  }

  /** Builder for {@link StreamableHttpServerParameters}. */
  public static class Builder {
    private final String url;
    private Map<String, String> headers;
    private Duration timeout = Duration.ofSeconds(30);
    private Duration sseReadTimeout = Duration.ofMinutes(5);
    private boolean terminateOnClose = true;

    private Builder(String url) {
      Assert.hasText(url, "url must not be empty");
      this.url = url;
    }

    @CanIgnoreReturnValue
    public Builder headers(Map<String, String> headers) {
      this.headers = headers;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder sseReadTimeout(Duration sseReadTimeout) {
      this.sseReadTimeout = sseReadTimeout;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder terminateOnClose(boolean terminateOnClose) {
      this.terminateOnClose = terminateOnClose;
      return this;
    }

    public StreamableHttpServerParameters build() {
      return new StreamableHttpServerParameters(
          url, headers, timeout, sseReadTimeout, terminateOnClose);
    }
  }
}
