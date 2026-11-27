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

import static com.google.common.base.Preconditions.checkState;

import com.google.adk.a2a.converters.A2APartConverter;
import com.google.adk.a2a.converters.FileUriPolicy;
import com.google.adk.a2a.converters.PartConverter;
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

  /**
   * Which file URIs from callers may reach the model; unset means {@link
   * FileUriPolicy#defaultPolicy()}, which admits none. Exclusive with {@link #a2aPartConverter()}.
   */
  public abstract @Nullable FileUriPolicy fileUriPolicy();

  /**
   * Converts each part of a caller's message, in place of {@link
   * PartConverter#defaultConverter(FileUriPolicy)}. Exclusive with {@link #fileUriPolicy()}; a
   * custom converter that wants a file URI policy delegates to {@code defaultConverter(policy)}.
   */
  public abstract @Nullable A2APartConverter a2aPartConverter();

  /** The converter {@link AgentExecutor} applies to each part of a caller's message. */
  A2APartConverter effectivePartConverter() {
    if (a2aPartConverter() != null) {
      return a2aPartConverter();
    }
    return PartConverter.defaultConverter(
        fileUriPolicy() != null ? fileUriPolicy() : FileUriPolicy.defaultPolicy());
  }

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

    @CanIgnoreReturnValue
    public abstract Builder fileUriPolicy(FileUriPolicy fileUriPolicy);

    @CanIgnoreReturnValue
    public abstract Builder a2aPartConverter(A2APartConverter a2aPartConverter);

    abstract AgentExecutorConfig autoBuild();

    /**
     * Builds the config.
     *
     * @throws IllegalStateException if both a file URI policy and a part converter are set
     */
    public AgentExecutorConfig build() {
      AgentExecutorConfig config = autoBuild();
      checkState(
          config.fileUriPolicy() == null || config.a2aPartConverter() == null,
          "Set fileUriPolicy or a2aPartConverter, not both; a custom converter can delegate to"
              + " PartConverter.defaultConverter(policy)");
      return config;
    }
  }
}
