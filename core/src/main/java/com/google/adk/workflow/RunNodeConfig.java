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
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Configuration for running a child node with {@link com.google.adk.agents.Context#runNode}. */
@Experimental
@AutoValue
public abstract class RunNodeConfig {

  /**
   * Returns the ID of the child's run, which names the run in node paths and branches. A child that
   * recorded an output under the same ID in an earlier turn returns it without running again. When
   * this ID is empty, the child receives the next number among the caller's runs of nodes with the
   * same name, in call order, so concurrent children need explicit IDs.
   */
  public abstract Optional<String> runId();

  /**
   * Returns whether the child's output is also the caller's output. When true, the caller must not
   * produce an output of its own, even if the child produces none, unless the child fails before
   * emitting its output.
   */
  public abstract boolean useAsOutput();

  /** Returns whether the child runs on its own sub-branch, {@code <branch>.<name>@<runId>}. */
  public abstract boolean useSubBranch();

  /** Returns the branch the child runs on instead of its caller's, if any. */
  public abstract Optional<String> overrideBranch();

  /**
   * Returns whether a {@link Workflow} or wait-for-output child that finishes without an output
   * ends its caller's run, as an interrupt does, instead of completing the call empty.
   */
  public abstract boolean raiseOnWait();

  public static Builder builder() {
    return new AutoValue_RunNodeConfig.Builder()
        .useAsOutput(false)
        .useSubBranch(false)
        .raiseOnWait(false);
  }

  public abstract Builder toBuilder();

  /** Builder for {@link RunNodeConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    /**
     * Sets the ID of the child's run, which must contain a non-digit character and must not contain
     * {@code /}, {@code @}, or {@code .}; null or empty generates an ID.
     */
    @CanIgnoreReturnValue
    public abstract Builder runId(@Nullable String runId);

    abstract Optional<String> runId();

    @CanIgnoreReturnValue
    public abstract Builder useAsOutput(boolean useAsOutput);

    @CanIgnoreReturnValue
    public abstract Builder useSubBranch(boolean useSubBranch);

    @CanIgnoreReturnValue
    public abstract Builder overrideBranch(@Nullable String overrideBranch);

    @CanIgnoreReturnValue
    public abstract Builder raiseOnWait(boolean raiseOnWait);

    abstract RunNodeConfig autoBuild();

    /**
     * Builds the config.
     *
     * @throws IllegalArgumentException if the run ID has no non-digit character or contains {@code
     *     /}, {@code @}, or {@code .}.
     */
    public final RunNodeConfig build() {
      if (runId().map(String::isEmpty).orElse(false)) {
        runId(null);
      }
      RunNodeConfig config = autoBuild();
      config
          .runId()
          .ifPresent(
              runId -> {
                // A run ID of digits only could collide with an ID the runtime generates.
                checkArgument(
                    !runId.chars().allMatch(Character::isDigit),
                    "runId \"%s\" must contain a non-digit character.",
                    runId);
                checkArgument(
                    NodeRunner.PATH_SEPARATORS.matchesNoneOf(runId),
                    "runId \"%s\" must not contain '/', '@', or '.'.",
                    runId);
              });
      return config;
    }
  }
}
