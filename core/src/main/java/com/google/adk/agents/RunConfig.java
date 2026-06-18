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
import com.google.genai.types.AvatarConfig;
import com.google.genai.types.Modality;
import com.google.genai.types.SpeechConfig;
import org.jspecify.annotations.Nullable;
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
   * Execution mode when the model requests multiple tools.
   *
   * <p>NONE: defaults to PARALLEL.
   *
   * <p>SEQUENTIAL: tools execute strictly in request order on the caller thread; each tool must
   * complete (including any asynchronous work) before the next one is subscribed to.
   *
   * <p>PARALLEL: tools are subscribed to eagerly on the caller thread (i.e. all are kicked off
   * up-front), but no worker threads are introduced. Tools that are truly asynchronous (e.g. they
   * return a {@code Single} backed by I/O or another scheduler) will run concurrently; tools that
   * block the subscribing thread (e.g. {@code Single.fromCallable} that performs blocking work)
   * will still execute sequentially. This preserves the historical default behavior.
   *
   * <p>PARALLEL_SUBSCRIBE: like {@code PARALLEL}, but every tool is additionally subscribed on a
   * worker thread, so blocking tools also run concurrently. Tool implementations must be
   * thread-safe. The worker is the agent's executor when set, otherwise the RxJava IO scheduler.
   */
  public enum ToolExecutionMode {
    NONE,
    SEQUENTIAL,
    PARALLEL,
    PARALLEL_SUBSCRIBE
  }

  public abstract @Nullable SpeechConfig speechConfig();

  public abstract ImmutableList<Modality> responseModalities();

  public abstract @Nullable AvatarConfig avatarConfig();

  public abstract boolean saveInputBlobsAsArtifacts();

  public abstract StreamingMode streamingMode();

  public abstract ToolExecutionMode toolExecutionMode();

  public abstract @Nullable AudioTranscriptionConfig outputAudioTranscription();

  public abstract @Nullable AudioTranscriptionConfig inputAudioTranscription();

  public abstract int maxLlmCalls();

  public abstract boolean autoCreateSession();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_RunConfig.Builder()
        .saveInputBlobsAsArtifacts(false)
        .responseModalities(ImmutableList.of())
        .streamingMode(StreamingMode.NONE)
        .toolExecutionMode(ToolExecutionMode.NONE)
        .maxLlmCalls(500)
        .autoCreateSession(false);
  }

  public static Builder builder(RunConfig runConfig) {
    return new AutoValue_RunConfig.Builder()
        .saveInputBlobsAsArtifacts(runConfig.saveInputBlobsAsArtifacts())
        .streamingMode(runConfig.streamingMode())
        .toolExecutionMode(runConfig.toolExecutionMode())
        .maxLlmCalls(runConfig.maxLlmCalls())
        .responseModalities(runConfig.responseModalities())
        .speechConfig(runConfig.speechConfig())
        .avatarConfig(runConfig.avatarConfig())
        .outputAudioTranscription(runConfig.outputAudioTranscription())
        .inputAudioTranscription(runConfig.inputAudioTranscription())
        .autoCreateSession(runConfig.autoCreateSession());
  }

  /** Builder for {@link RunConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setSpeechConfig(@Nullable SpeechConfig speechConfig) {
      return speechConfig(speechConfig);
    }

    @CanIgnoreReturnValue
    public abstract Builder speechConfig(@Nullable SpeechConfig speechConfig);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setResponseModalities(Iterable<Modality> responseModalities) {
      return responseModalities(responseModalities);
    }

    @CanIgnoreReturnValue
    public abstract Builder responseModalities(Iterable<Modality> responseModalities);

    @CanIgnoreReturnValue
    public abstract Builder avatarConfig(@Nullable AvatarConfig avatarConfig);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setSaveInputBlobsAsArtifacts(boolean saveInputBlobsAsArtifacts) {
      return saveInputBlobsAsArtifacts(saveInputBlobsAsArtifacts);
    }

    @CanIgnoreReturnValue
    public abstract Builder saveInputBlobsAsArtifacts(boolean saveInputBlobsAsArtifacts);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setStreamingMode(StreamingMode streamingMode) {
      return streamingMode(streamingMode);
    }

    @CanIgnoreReturnValue
    public abstract Builder streamingMode(StreamingMode streamingMode);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setToolExecutionMode(ToolExecutionMode toolExecutionMode) {
      return toolExecutionMode(toolExecutionMode);
    }

    @CanIgnoreReturnValue
    public abstract Builder toolExecutionMode(ToolExecutionMode toolExecutionMode);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setOutputAudioTranscription(
        @Nullable AudioTranscriptionConfig outputAudioTranscription) {
      return outputAudioTranscription(outputAudioTranscription);
    }

    @CanIgnoreReturnValue
    public abstract Builder outputAudioTranscription(
        @Nullable AudioTranscriptionConfig outputAudioTranscription);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setInputAudioTranscription(
        @Nullable AudioTranscriptionConfig inputAudioTranscription) {
      return inputAudioTranscription(inputAudioTranscription);
    }

    @CanIgnoreReturnValue
    public abstract Builder inputAudioTranscription(
        @Nullable AudioTranscriptionConfig inputAudioTranscription);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setMaxLlmCalls(int maxLlmCalls) {
      return maxLlmCalls(maxLlmCalls);
    }

    @CanIgnoreReturnValue
    public abstract Builder maxLlmCalls(int maxLlmCalls);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setAutoCreateSession(boolean autoCreateSession) {
      return autoCreateSession(autoCreateSession);
    }

    @CanIgnoreReturnValue
    public abstract Builder autoCreateSession(boolean autoCreateSession);

    abstract RunConfig autoBuild();

    public RunConfig build() {
      RunConfig runConfig = autoBuild();
      if (runConfig.maxLlmCalls() == Integer.MAX_VALUE) {
        throw new IllegalArgumentException("maxLlmCalls should be less than Integer.MAX_VALUE.");
      }
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
