/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.agents;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Modality;
import com.google.genai.types.SpeechConfig;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Configuration to modify an agent's LLM's underlying behavior. */
@AutoValue
public abstract class RunConfig {
  private static final Logger logger = LoggerFactory.getLogger(RunConfig.class);

  /** Streaming mode for the runner. Required for BaseAgent.runLive() to work. */
  public enum StreamingMode {
    NONE,
    SSE,
    BIDI
  }

  /**
   * Tool execution mode for the runner, when they are multiple tools requested (by the models or
   * callbacks).
   *
   * <p>NONE: default to PARALLEL.
   *
   * <p>SEQUENTIAL: Multiple tools are executed in the order they are requested.
   *
   * <p>PARALLEL: Multiple tools are executed in parallel.
   */
  public enum ToolExecutionMode {
    NONE,
    SEQUENTIAL,
    PARALLEL
  }

  public abstract @Nullable SpeechConfig speechConfig();

  public abstract ImmutableList<Modality> responseModalities();

  public abstract boolean saveInputBlobsAsArtifacts();

  public abstract StreamingMode streamingMode();

  public abstract ToolExecutionMode toolExecutionMode();

  public abstract @Nullable AudioTranscriptionConfig outputAudioTranscription();

  public abstract @Nullable AudioTranscriptionConfig inputAudioTranscription();

  public abstract int maxLlmCalls();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_RunConfig.Builder()
        .setSaveInputBlobsAsArtifacts(false)
        .setResponseModalities(ImmutableList.of())
        .setStreamingMode(StreamingMode.NONE)
        .setToolExecutionMode(ToolExecutionMode.NONE)
        .setMaxLlmCalls(500);
  }

  public static Builder builder(RunConfig runConfig) {
    return new AutoValue_RunConfig.Builder()
        .setSaveInputBlobsAsArtifacts(runConfig.saveInputBlobsAsArtifacts())
        .setStreamingMode(runConfig.streamingMode())
        .setToolExecutionMode(runConfig.toolExecutionMode())
        .setMaxLlmCalls(runConfig.maxLlmCalls())
        .setResponseModalities(runConfig.responseModalities())
        .setSpeechConfig(runConfig.speechConfig())
        .setOutputAudioTranscription(runConfig.outputAudioTranscription())
        .setInputAudioTranscription(runConfig.inputAudioTranscription());
  }

  /** Builder for {@link RunConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder setSpeechConfig(@Nullable SpeechConfig speechConfig);

    @CanIgnoreReturnValue
    public abstract Builder setResponseModalities(Iterable<Modality> responseModalities);

    @CanIgnoreReturnValue
    public abstract Builder setSaveInputBlobsAsArtifacts(boolean saveInputBlobsAsArtifacts);

    @CanIgnoreReturnValue
    public abstract Builder setStreamingMode(StreamingMode streamingMode);

    @CanIgnoreReturnValue
    public abstract Builder setToolExecutionMode(ToolExecutionMode toolExecutionMode);

    @CanIgnoreReturnValue
    public abstract Builder setOutputAudioTranscription(
        @Nullable AudioTranscriptionConfig outputAudioTranscription);

    @CanIgnoreReturnValue
    public abstract Builder setInputAudioTranscription(
        @Nullable AudioTranscriptionConfig inputAudioTranscription);

    @CanIgnoreReturnValue
    public abstract Builder setMaxLlmCalls(int maxLlmCalls);

    abstract RunConfig autoBuild();

    public RunConfig build() {
      RunConfig runConfig = autoBuild();
      if (runConfig.maxLlmCalls() < 0) {
        logger.warn(
            "maxLlmCalls is negative. This will result in no enforcement on total"
                + " number of llm calls that will be made for a run. This may not be ideal, as this"
                + " could result in a never ending communication between the model and the agent in"
                + " certain cases.");
      }
      return runConfig;
    }
  }
}
