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

package com.google.adk.sessions;

import com.google.auto.value.AutoValue;
import java.time.Instant;
import java.util.Optional;

/** Configuration for getting a session. */
@AutoValue
public abstract class GetSessionConfig {

  public abstract Optional<Integer> numRecentEvents();

  public abstract Optional<Instant> afterTimestamp();

  /** Builder for {@link GetSessionConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder numRecentEvents(int numRecentEvents);

    public abstract Builder afterTimestamp(Instant afterTimestamp);

    public abstract GetSessionConfig build();
  }

  public static Builder builder() {
    return new AutoValue_GetSessionConfig.Builder();
  }
}
