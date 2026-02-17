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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import javax.annotation.Nullable;

/** Parameters for establishing a MCP Server-Sent Events (SSE) connection. */
@AutoValue
@JsonDeserialize(builder = SseServerParameters.Builder.class)
public abstract class SseServerParameters {

  /** The URL of the SSE server. */
  public abstract String url();

  /** The endpoint to connect to on the SSE server. */
  @Nullable
  public abstract String sseEndpoint();

  /** Optional headers to include in the SSE connection request. */
  @Nullable
  public abstract ImmutableMap<String, Object> headers();

  /** The timeout for the initial connection attempt. */
  @Nullable
  public abstract Duration timeout();

  /** The timeout for reading data from the SSE stream. */
  @Nullable
  public abstract Duration sseReadTimeout();

  /** Creates a new builder for {@link SseServerParameters}. */
  public static Builder builder() {
    return new AutoValue_SseServerParameters.Builder()
        .timeout(Duration.ofSeconds(5))
        .sseReadTimeout(Duration.ofMinutes(5));
  }

  /** Builder for {@link SseServerParameters}. */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public abstract static class Builder {

    @JsonCreator
    static SseServerParameters.Builder jacksonBuilder() {
      return SseServerParameters.builder();
    }

    /** Sets the URL of the SSE server. */
    public abstract Builder url(String url);

    /** Sets the endpoint to connect to on the SSE server. */
    public abstract Builder sseEndpoint(String sseEndpoint);

    /** Sets the headers for the SSE connection request. */
    public abstract Builder headers(@Nullable Map<String, Object> headers);

    /** Sets the timeout for the initial connection attempt. */
    public abstract Builder timeout(@Nullable Duration timeout);

    /** Sets the timeout for reading data from the SSE stream. */
    public abstract Builder sseReadTimeout(@Nullable Duration sseReadTimeout);

    /** Builds a new {@link SseServerParameters} instance. */
    public abstract SseServerParameters build();
  }
}
