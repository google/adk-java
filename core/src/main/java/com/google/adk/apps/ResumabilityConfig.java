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
 * App resumability config, mirroring Python ADK's experimental {@code ResumabilityConfig}: pause on
 * a long-running call and resume from the last event. Applies to all agents in the app.
 *
 * @deprecated Experimental and not yet stable: resume is best-effort and at-least-once, so a
 *     resuming tool must be idempotent and any temporary in-memory state is lost on resumption.
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
