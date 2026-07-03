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

package com.google.adk.web.service;

import com.google.auto.value.AutoValue;
import java.util.Optional;

/** Configuration for {@link ApiServerSpanExporter}. */
@AutoValue
public abstract class ApiServerSpanExporterConfig {

  /**
   * The maximum number of spans to keep in memory. When the limit is reached, the oldest spans are
   * evicted (FIFO). If empty, no limit is enforced and spans accumulate without bound.
   *
   * <p>When set, the value must be a positive integer ({@code >= 1}).
   */
  public abstract Optional<Integer> maxSpansToKeep();

  public static Builder builder() {
    return new AutoValue_ApiServerSpanExporterConfig.Builder();
  }

  /** Builder for {@link ApiServerSpanExporterConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder maxSpansToKeep(Optional<Integer> maxSpansToKeep);

    abstract ApiServerSpanExporterConfig autoBuild();

    public final ApiServerSpanExporterConfig build() {
      ApiServerSpanExporterConfig config = autoBuild();
      config
          .maxSpansToKeep()
          .ifPresent(
              max -> {
                if (max < 1) {
                  throw new IllegalArgumentException(
                      "maxSpansToKeep must be >= 1 when set, got: " + max);
                }
              });
      return config;
    }
  }
}
