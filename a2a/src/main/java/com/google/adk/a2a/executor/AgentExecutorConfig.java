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

  private static final RunConfig DEFAULT_RUN_CONFIG =
      RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.NONE).setMaxLlmCalls(20).build();

  public abstract RunConfig runConfig();

  public abstract @Nullable BeforeExecuteCallback beforeExecuteCallback();

  public abstract @Nullable AfterExecuteCallback afterExecuteCallback();

  public abstract @Nullable AfterEventCallback afterEventCallback();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_AgentExecutorConfig.Builder().runConfig(DEFAULT_RUN_CONFIG);
  }

  /** Builder for {@link AgentExecutorConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    public abstract Builder runConfig(RunConfig runConfig);

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
