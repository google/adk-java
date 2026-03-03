/*
 * Copyright 2025 Google LLC
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

package com.google.adk.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponsePromptFeedback;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Represents a response received from the LLM. */
@AutoValue
@JsonDeserialize(builder = LlmResponse.Builder.class)
public abstract class LlmResponse extends JsonBaseModel {

  LlmResponse() {}

  /**
   * Returns the content of the first candidate in the response, if available.
   *
   * @return An {@link Content} of the first {@link Candidate} in the {@link
   *     GenerateContentResponse} if the response contains at least one candidate., or an empty
   *     optional if no candidates are present in the response.
   */
  @JsonProperty("content")
  public abstract Optional<Content> content();

  /**
   * Returns the grounding metadata of the first candidate in the response, if available.
   *
   * @return An {@link Optional} containing {@link GroundingMetadata} or empty.
   */
  @JsonProperty("groundingMetadata")
  public abstract Optional<GroundingMetadata> groundingMetadata();

  /**
   * Returns the custom metadata of the response, if available.
   *
   * @return An {@link Optional} containing a list of {@link CustomMetadata} or empty.
   */
  @JsonProperty("customMetadata")
  public abstract Optional<List<CustomMetadata>> customMetadata();

  /**
   * Indicates whether the text content is part of a unfinished text stream.
   *
   * <p>Only used for streaming mode and when the content is plain text.
   */
  @JsonProperty("partial")
  public abstract Optional<Boolean> partial();

  /**
   * Indicates whether the response from the model is complete.
   *
   * <p>Only used for streaming mode.
   */
  @JsonProperty("turnComplete")
  public abstract Optional<Boolean> turnComplete();

  /** Error code if the response is an error. Code varies by model. */
  @JsonProperty("errorCode")
  public abstract Optional<FinishReason> errorCode();

  /** Error code if the response is an error. Code varies by model. */
  @JsonProperty("finishReason")
  public abstract Optional<FinishReason> finishReason();

  /** Error code if the response is an error. Code varies by model. */
  @JsonProperty("avgLogprobs")
  public abstract Optional<Double> avgLogprobs();

  /** Error message if the response is an error. */
  @JsonProperty("errorMessage")
  public abstract Optional<String> errorMessage();

  /**
   * Indicates that LLM was interrupted when generating the content. Usually it's due to user
   * interruption during a bidi streaming.
   */
  @JsonProperty("interrupted")
  public abstract Optional<Boolean> interrupted();

  /** Usage metadata about the response(s). */
  @JsonProperty("usageMetadata")
  public abstract Optional<GenerateContentResponseUsageMetadata> usageMetadata();

  /** The model version used to generate the response. */
  @JsonProperty("modelVersion")
  public abstract Optional<String> modelVersion();

  public abstract Builder toBuilder();

  /** Builder for constructing {@link LlmResponse} instances. */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public abstract static class Builder {

    @JsonCreator
    static LlmResponse.Builder jacksonBuilder() {
      return LlmResponse.builder();
    }

    @JsonProperty("content")
    public abstract Builder content(Content content);

    public abstract Builder content(Optional<Content> content);

    @JsonProperty("interrupted")
    public abstract Builder interrupted(@Nullable Boolean interrupted);

    public abstract Builder interrupted(Optional<Boolean> interrupted);

    @JsonProperty("groundingMetadata")
    public abstract Builder groundingMetadata(@Nullable GroundingMetadata groundingMetadata);

    public abstract Builder groundingMetadata(Optional<GroundingMetadata> groundingMetadata);

    @JsonProperty("customMetadata")
    public abstract Builder customMetadata(@Nullable List<CustomMetadata> customMetadata);

    @JsonProperty("partial")
    public abstract Builder partial(@Nullable Boolean partial);

    public abstract Builder partial(Optional<Boolean> partial);

    @JsonProperty("turnComplete")
    public abstract Builder turnComplete(@Nullable Boolean turnComplete);

    public abstract Builder turnComplete(Optional<Boolean> turnComplete);

    @JsonProperty("errorCode")
    public abstract Builder errorCode(@Nullable FinishReason errorCode);

    public abstract Builder errorCode(Optional<FinishReason> errorCode);

    @JsonProperty("finishReason")
    public abstract Builder finishReason(@Nullable FinishReason finishReason);

    public abstract Builder finishReason(Optional<FinishReason> finishReason);

    @JsonProperty("avgLogprobs")
    public abstract Builder avgLogprobs(@Nullable Double avgLogprobs);

    public abstract Builder avgLogprobs(Optional<Double> avgLogprobs);

    @JsonProperty("errorMessage")
    public abstract Builder errorMessage(@Nullable String errorMessage);

    public abstract Builder errorMessage(Optional<String> errorMessage);

    @JsonProperty("usageMetadata")
    public abstract Builder usageMetadata(
        @Nullable GenerateContentResponseUsageMetadata usageMetadata);

    public abstract Builder usageMetadata(
        Optional<GenerateContentResponseUsageMetadata> usageMetadata);

    @JsonProperty("modelVersion")
    public abstract Builder modelVersion(@Nullable String modelVersion);

    public abstract Builder modelVersion(Optional<String> modelVersion);

    @CanIgnoreReturnValue
    public final Builder response(GenerateContentResponse response) {
      Optional<List<Candidate>> candidatesOpt = response.candidates();
      if (candidatesOpt.isPresent() && !candidatesOpt.get().isEmpty()) {
        Candidate candidate = candidatesOpt.get().get(0);
        this.finishReason(candidate.finishReason());
        if (candidate.content().isPresent()) {
          this.content(candidate.content().get());
          this.groundingMetadata(candidate.groundingMetadata());
        } else {
          candidate.finishReason().ifPresent(this::errorCode);
          candidate.finishMessage().ifPresent(this::errorMessage);
        }
      } else {
        Optional<GenerateContentResponsePromptFeedback> promptFeedbackOpt =
            response.promptFeedback();
        if (promptFeedbackOpt.isPresent()) {
          GenerateContentResponsePromptFeedback promptFeedback = promptFeedbackOpt.get();
          promptFeedback
              .blockReason()
              .ifPresent(reason -> this.errorCode(new FinishReason(reason.toString())));
          promptFeedback.blockReasonMessage().ifPresent(this::errorMessage);
        } else {
          this.errorCode(new FinishReason("Unknown error."));
          this.errorMessage("Unknown error.");
        }
      }
      this.usageMetadata(response.usageMetadata());
      this.modelVersion(response.modelVersion());
      return this;
    }

    abstract LlmResponse autoBuild();

    public LlmResponse build() {
      return autoBuild();
    }
  }

  public static Builder builder() {
    return new AutoValue_LlmResponse.Builder();
  }

  public static LlmResponse create(List<Candidate> candidates) {
    GenerateContentResponse response =
        GenerateContentResponse.builder().candidates(candidates).build();
    return builder().response(response).build();
  }

  public static LlmResponse create(GenerateContentResponse response) {
    return builder().response(response).build();
  }
}
