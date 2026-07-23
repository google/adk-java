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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.auto.value.AutoValue;
import java.time.Duration;

@AutoValue
@JsonDeserialize(builder = StdioConnectionParameters.Builder.class)
public abstract class StdioConnectionParameters {

  private static final long MILLIS_IN_SEC = 1000L;
  private static final float DEFAULT_TIMEOUT_SECS = 5f;

  StdioConnectionParameters() {}

  public abstract StdioServerParameters serverParams();

  // Timeout in seconds
  public abstract float timeout();

  @JsonIgnore
  public Duration timeoutDuration() {
    return Duration.ofMillis((long) (timeout() * MILLIS_IN_SEC));
  }

  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public abstract static class Builder {

    @JsonCreator
    public static Builder jacksonBuilder() {
      return StdioConnectionParameters.builder();
    }

    public abstract Builder serverParams(StdioServerParameters serverParams);

    public abstract Builder timeout(float timeout);

    public abstract StdioConnectionParameters build();
  }

  public static Builder builder() {
    Builder b = new AutoValue_StdioConnectionParameters.Builder();
    return b.timeout(DEFAULT_TIMEOUT_SECS);
  }
}
