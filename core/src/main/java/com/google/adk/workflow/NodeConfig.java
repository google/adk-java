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

package com.google.adk.workflow;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A node's retry policy and execution timeout. */
@Experimental
@AutoValue
public abstract class NodeConfig {

  /** Returns how the node is retried when it fails; empty does not retry. */
  public abstract Optional<RetryConfig> retryConfig();

  /**
   * Returns how long an attempt may run before it is cancelled and treated as a failure; empty
   * imposes no limit.
   */
  public abstract Optional<Duration> timeout();

  public static Builder builder() {
    return new AutoValue_NodeConfig.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link NodeConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder retryConfig(@Nullable RetryConfig retryConfig);

    /** Sets the timeout, which must be positive; null imposes no limit. */
    @CanIgnoreReturnValue
    public abstract Builder timeout(@Nullable Duration timeout);

    abstract NodeConfig autoBuild();

    public final NodeConfig build() {
      NodeConfig config = autoBuild();
      // A zero or negative timeout would fire at once.
      checkArgument(
          config.timeout().map(timeout -> timeout.compareTo(Duration.ZERO) > 0).orElse(true),
          "timeout must be positive, or null for no timeout.");
      return config;
    }
  }
}
