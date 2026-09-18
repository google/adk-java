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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * App resumability config: pause on a long-running call and resume from the last event. Applies to
 * all agents in the app.
 *
 * <p>The two flags select the resumption flow and are mutually exclusive. With neither set the app
 * never pauses; {@link #isResumable()} checkpoints agent state and resumes from it; {@link
 * #isPlainTextContinuationAutoResume()} selects the deprecated legacy flow.
 *
 * <p>Experimental and not yet stable: resume is best-effort and at-least-once, so a resuming tool
 * must be idempotent and any temporary in-memory state is lost on resumption.
 */
@Experimental
@AutoValue
public abstract class ResumabilityConfig {

  /** Whether the app supports agent resumption, checkpointing agent state as it runs. */
  public abstract boolean isResumable();

  /**
   * Whether a plain-text {@code runAsync} continuation -- a user message that is not a function
   * response -- resumes the last unfinished invocation instead of starting a new one. Off by
   * default: a plain-text {@code runAsync} starts a new invocation and a paused invocation is
   * resumed explicitly.
   *
   * <p>Selects the legacy resumption flow, which reconstructs the resume point from session events
   * and never persists agent state. Mutually exclusive with {@link #isResumable()}.
   *
   * @deprecated Back-compat shim for callers that deliver a resume as a plain-text turn. Migrate to
   *     {@code Runner.runAsync(userId, sessionId, invocationId, message, runConfig, stateDelta)},
   *     which resumes the invocation named by {@code invocationId}, or answer the paused call with
   *     a function response; both need {@link #isResumable()} instead of this flag, which will be
   *     removed.
   */
  @Deprecated
  public abstract boolean isPlainTextContinuationAutoResume();

  public static Builder builder() {
    return new AutoValue_ResumabilityConfig.Builder()
        .resumable(false)
        .plainTextContinuationAutoResume(false);
  }

  /** Builder for {@link ResumabilityConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    public abstract Builder resumable(boolean isResumable);

    /**
     * @deprecated Back-compat shim only; migrate to {@code Runner.runAsync(...)} with an invocation
     *     id (or send a function response to the paused call). See {@link
     *     ResumabilityConfig#isPlainTextContinuationAutoResume()}.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public abstract Builder plainTextContinuationAutoResume(boolean value);

    abstract ResumabilityConfig autoBuild();

    /**
     * Builds the config, rejecting a combination of flags that has no defined behavior.
     *
     * @throws IllegalArgumentException if both resumability and the legacy shim are enabled; they
     *     select different resumption flows, so exactly one may be set.
     */
    public ResumabilityConfig build() {
      ResumabilityConfig config = autoBuild();
      checkArgument(
          !(config.isResumable() && config.isPlainTextContinuationAutoResume()),
          "resumable and plainTextContinuationAutoResume are mutually exclusive: set resumable for"
              + " the supported flow, or the deprecated shim for the legacy one.");
      return config;
    }
  }
}
