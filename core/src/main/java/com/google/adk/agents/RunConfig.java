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
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.AvatarConfig;
import com.google.genai.types.Modality;
import com.google.genai.types.SpeechConfig;
import java.util.Map;
import java.util.Optional;
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

  /**
   * Three-state override for grouping function calls before function responses in history (FC1,
   * FC2, FR1, FR2) instead of pairing each response with its call (FC1, FR1, FC2, FR2).
   *
   * <p>Empty (default) groups only for models that require it (Gemini 3); when present the value
   * applies to all models.
   *
   * <p>Not needed for the core ADK Gemini implementation, which already groups automatically for
   * Gemini 3. Kept for backwards compatibility with other model implementations that route to
   * endpoints requiring the grouped form.
   *
   * @deprecated Expected only for specific model endpoints.
   */
  @Deprecated
  public abstract Optional<Boolean> groupFunctionResponsesInHistoryOverride();

  /**
   * Whether grouping is explicitly enabled; equivalent to {@code
   * groupFunctionResponsesInHistoryOverride().orElse(false)}. Retained for backwards compatibility.
   *
   * @deprecated Expected only for specific model endpoints.
   */
  @Deprecated
  @SuppressWarnings("deprecation") // Delegates to the deprecated override accessor.
  public final boolean groupFunctionResponsesInHistory() {
    return groupFunctionResponsesInHistoryOverride().orElse(false);
  }

  public abstract ImmutableMap<String, Object> customMetadata();

  public abstract Builder toBuilder();

  public static Builder builder() {
    // Leave grouping override unset so it defaults on only for models that require it (Gemini 3).
    return new AutoValue_RunConfig.Builder()
        .saveInputBlobsAsArtifacts(false)
        .responseModalities(ImmutableList.of())
        .streamingMode(StreamingMode.NONE)
        .toolExecutionMode(ToolExecutionMode.NONE)
        .maxLlmCalls(500)
        .autoCreateSession(false)
        .customMetadata(ImmutableMap.of());
  }

  @SuppressWarnings("deprecation") // Propagates the workaround flag.
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
        .autoCreateSession(runConfig.autoCreateSession())
        .groupFunctionResponsesInHistoryOverride(
            runConfig.groupFunctionResponsesInHistoryOverride())
        .customMetadata(runConfig.customMetadata());
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

    @CanIgnoreReturnValue
    public abstract Builder customMetadata(Map<String, Object> customMetadata);

    /**
     * Sets the three-state grouping override.
     *
     * @deprecated Expected only for specific model endpoints.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public abstract Builder groupFunctionResponsesInHistoryOverride(
        Optional<Boolean> groupFunctionResponsesInHistoryOverride);

    /**
     * @deprecated Expected only for specific model endpoints.
     */
    @Deprecated
    @SuppressWarnings("deprecation") // Delegates to the deprecated override setter.
    @CanIgnoreReturnValue
    public final Builder groupFunctionResponsesInHistoryOverride(
        boolean groupFunctionResponsesInHistoryOverride) {
      return groupFunctionResponsesInHistoryOverride(
          Optional.of(groupFunctionResponsesInHistoryOverride));
    }

    /**
     * Backwards-compatible alias for {@link #groupFunctionResponsesInHistoryOverride(boolean)}.
     *
     * @deprecated Expected only for specific model endpoints.
     */
    @Deprecated
    @SuppressWarnings("deprecation") // Delegates to the deprecated override setter.
    @CanIgnoreReturnValue
    public final Builder groupFunctionResponsesInHistory(boolean groupFunctionResponsesInHistory) {
      return groupFunctionResponsesInHistoryOverride(groupFunctionResponsesInHistory);
    }

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
