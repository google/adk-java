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
package com.google.adk.a2a.executor;

import com.google.adk.a2a.executor.Callbacks.AfterEventCallback;
import com.google.adk.a2a.executor.Callbacks.AfterExecuteCallback;
import com.google.adk.a2a.executor.Callbacks.BeforeExecuteCallback;
import com.google.adk.agents.RunConfig;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.jspecify.annotations.Nullable;

/** Configuration for the {@link AgentExecutor}. */
@AutoValue
public abstract class AgentExecutorConfig {

  /**
   * Output mode for the agent executor.
   *
   * <p>ARTIFACT_PER_RUN: The agent executor will return one artifact per run.
   *
   * <p>ARTIFACT_PER_EVENT: The agent executor will return one artifact per event.
   */
  public enum OutputMode {
    ARTIFACT_PER_RUN,
    ARTIFACT_PER_EVENT
  }

  private static final RunConfig DEFAULT_RUN_CONFIG =
      RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.NONE).setMaxLlmCalls(20).build();

  public abstract RunConfig runConfig();

  public abstract OutputMode outputMode();

  public abstract @Nullable BeforeExecuteCallback beforeExecuteCallback();

  public abstract @Nullable AfterExecuteCallback afterExecuteCallback();

  public abstract @Nullable AfterEventCallback afterEventCallback();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_AgentExecutorConfig.Builder()
        .runConfig(DEFAULT_RUN_CONFIG)
        .outputMode(OutputMode.ARTIFACT_PER_RUN);
  }

  /** Builder for {@link AgentExecutorConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    public abstract Builder runConfig(RunConfig runConfig);

    @CanIgnoreReturnValue
    public abstract Builder outputMode(OutputMode outputMode);

    @CanIgnoreReturnValue
    public abstract Builder beforeExecuteCallback(BeforeExecuteCallback beforeExecuteCallback);

    @CanIgnoreReturnValue
    public abstract Builder afterExecuteCallback(AfterExecuteCallback afterExecuteCallback);

    @CanIgnoreReturnValue
    public abstract Builder afterEventCallback(AfterEventCallback afterEventCallback);

    abstract AgentExecutorConfig autoBuild();

    public AgentExecutorConfig build() {
      return autoBuild();
    }
  }
}
