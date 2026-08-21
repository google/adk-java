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

package com.google.adk.apps;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * App resumability config, mirroring Python ADK v1's {@code ResumabilityConfig}: pause on a
 * long-running call and resume from the last event. Applies to all agents in the app.
 *
 * @deprecated Partial feature: only event-reconstruction-based pause/resume for {@code
 *     SequentialAgent} is implemented. Full session resumability (persisted agent state, durable
 *     resume, other workflow agents) is not yet available. Forward-compatible: the same config will
 *     drive full resumability once it lands.
 */
@Deprecated
@AutoValue
public abstract class ResumabilityConfig {

  /** Whether the app supports agent resumption. */
  public abstract boolean isResumable();

  public static Builder builder() {
    return new AutoValue_ResumabilityConfig.Builder().resumable(false);
  }

  /** Builder for {@link ResumabilityConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    public abstract Builder resumable(boolean isResumable);

    public abstract ResumabilityConfig build();
  }
}
