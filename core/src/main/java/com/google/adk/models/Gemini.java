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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the Gemini Generative AI model.
 *
 * <p>This class provides methods for interacting with the Gemini model, including standard
 * request-response generation and establishing persistent bidirectional connections.
 */
public class Gemini extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(Gemini.class);

  private final Client apiClient;

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  /**
   * Constructs a new Gemini instance.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiClient The genai {@link com.google.genai.Client} instance for making API calls.
   */
  public Gemini(String modelName, Client apiClient) {
    super(modelName);
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient cannot be null");
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiKey The Google Gemini API key.
   */
  public Gemini(String modelName, String apiKey) {
    super(modelName);
    Objects.requireNonNull(apiKey, "apiKey cannot be null");
    this.apiClient = Client.builder().apiKey(apiKey).build();
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param vertexCredentials The Vertex AI credentials to access the Gemini model.
   */
  public Gemini(String modelName, VertexCredentials vertexCredentials) {
    super(modelName);
    Objects.requireNonNull(vertexCredentials, "vertexCredentials cannot be null");
    Client.Builder apiClientBuilder = Client.builder();
    vertexCredentials
        .project()
        .ifPresent(
            project -> {
              var unused = apiClientBuilder.project(project);
            });
    vertexCredentials
        .location()
        .ifPresent(
            location -> {
              var unused = apiClientBuilder.location(location);
            });
    vertexCredentials
        .credentials()
        .ifPresent(
            credentials -> {
              var unused = apiClientBuilder.credentials(credentials);
            });
    this.apiClient = apiClientBuilder.build();
  }

  /**
   * Returns a new Builder instance for constructing Gemini objects. Note that when building a
   * Gemini object, at least one of apiKey, vertexCredentials, or an explicit apiClient must be set.
   * If multiple are set, the explicit apiClient will take precedence.
   *
   * @return A new {@link Builder}.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link Gemini}. */
  public static class Builder {
    private String modelName;
    private Client apiClient;
    private String apiKey;
    private VertexCredentials vertexCredentials;

    private Builder() {}

    /**
     * Sets the name of the Gemini model to use.
     *
     * @param modelName The model name (e.g., "gemini-2.0-flash").
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    /**
     * Sets the explicit {@link com.google.genai.Client} instance for making API calls. If this is
     * set, apiKey and vertexCredentials will be ignored.
     *
     * @param apiClient The client instance.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder apiClient(Client apiClient) {
      this.apiClient = apiClient;
      return this;
    }

    /**
     * Sets the Google Gemini API key. If {@link #apiClient(Client)} is also set, the explicit
     * client will take precedence. If {@link #vertexCredentials(VertexCredentials)} is also set,
     * this apiKey will take precedence.
     *
     * @param apiKey The API key.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the Vertex AI credentials. If {@link #apiClient(Client)} or {@link #apiKey(String)} are
     * also set, they will take precedence over these credentials.
     *
     * @param vertexCredentials The Vertex AI credentials.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder vertexCredentials(VertexCredentials vertexCredentials) {
      this.vertexCredentials = vertexCredentials;
      return this;
    }

    /**
     * Builds the {@link Gemini} instance.
     *
     * @return A new {@link Gemini} instance.
     * @throws NullPointerException if modelName is null.
     * @throws IllegalStateException if none of apiKey, VertexCredentials, or an explicit apiClient
     *     is set.
     */
    public Gemini build() {
      Objects.requireNonNull(modelName, "modelName must be set.");

      if (apiClient != null) {
        return new Gemini(modelName, apiClient);
      } else if (apiKey != null) {
        return new Gemini(modelName, apiKey);
      } else if (vertexCredentials != null) {
        return new Gemini(modelName, vertexCredentials);
      } else {
        throw new IllegalStateException(
            "Authentication strategy not set: Either apiKey, VertexCredentials, or an explicit"
                + " apiClient must be provided.");
      }
    }
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {

    List<Content> contents = llmRequest.contents();
    // Last content must be from the user, otherwise the model won't respond.
    if (contents.isEmpty() || !contents.get(contents.size() - 1).role().orElse("").equals("user")) {
      Content userContent =
          Content.builder()
              .role("user")
              .parts(ImmutableList.of(Part.builder().text(CONTINUE_OUTPUT_MESSAGE).build()))
              .build();
      contents =
          Stream.concat(contents.stream(), Stream.of(userContent)).collect(Collectors.toList());
    }

    List<Content> finalContents = stripThoughts(contents);
    GenerateContentConfig config = llmRequest.config().orElse(null);
    String effectiveModelName = llmRequest.model().orElse(model());

    logger.trace("Request Contents: {}", finalContents);
    logger.trace("Request Config: {}", config);

    if (stream) {
      logger.debug("Sending streaming generateContent request to model {}", effectiveModelName);
      CompletableFuture<ResponseStream<GenerateContentResponse>> streamFuture =
          apiClient.async.models.generateContentStream(effectiveModelName, finalContents, config);

      return Flowable.defer(
          () -> {
            final StringBuilder accumulatedText = new StringBuilder();
            // Array to bypass final local variable reassignment in lambda.
            final GenerateContentResponse[] lastRawResponseHolder = {null};

            return Flowable.fromFuture(streamFuture)
                .flatMapIterable(iterable -> iterable)
                .concatMap(
                    rawResponse -> {
                      lastRawResponseHolder[0] = rawResponse;
                      logger.trace("Raw streaming response: {}", rawResponse);

                      List<LlmResponse> responsesToEmit = new ArrayList<>();
                      LlmResponse currentProcessedLlmResponse = LlmResponse.create(rawResponse);
                      String currentTextChunk = getTextFromLlmResponse(currentProcessedLlmResponse);

                      if (!currentTextChunk.isEmpty()) {
                        accumulatedText.append(currentTextChunk);
                        LlmResponse partialResponse =
                            currentProcessedLlmResponse.toBuilder().partial(true).build();
                        responsesToEmit.add(partialResponse);
                      } else {
                        if (accumulatedText.length() > 0
                            && shouldEmitAccumulatedText(currentProcessedLlmResponse)) {
                          LlmResponse aggregatedTextResponse =
                              LlmResponse.builder()
                                  .content(
                                      Content.builder()
                                          .parts(
                                              ImmutableList.of(
                                                  Part.builder()
                                                      .text(accumulatedText.toString())
                                                      .build()))
                                          .build())
                                  .build();
                          responsesToEmit.add(aggregatedTextResponse);
                          accumulatedText.setLength(0);
                        }
                        responsesToEmit.add(currentProcessedLlmResponse);
                      }
                      logger.debug("Responses to emit: {}", responsesToEmit);
                      return Flowable.fromIterable(responsesToEmit);
                    })
                .concatWith(
                    Flowable.defer(
                        () -> {
                          if (accumulatedText.length() > 0 && lastRawResponseHolder[0] != null) {
                            GenerateContentResponse finalRawResp = lastRawResponseHolder[0];
                            boolean isStop =
                                finalRawResp
                                    .candidates()
                                    .flatMap(
                                        candidates ->
                                            candidates.isEmpty()
                                                ? Optional.empty()
                                                : Optional.of(candidates.get(0)))
                                    .flatMap(Candidate::finishReason)
                                    .map(
                                        finishReason ->
                                            finishReason.equals(
                                                new FinishReason(FinishReason.Known.STOP)))
                                    .orElse(false);

                            if (isStop) {
                              LlmResponse finalAggregatedTextResponse =
                                  LlmResponse.builder()
                                      .content(
                                          Content.builder()
                                              .parts(
                                                  ImmutableList.of(
                                                      Part.builder()
                                                          .text(accumulatedText.toString())
                                                          .build()))
                                              .build())
                                      .build();
                              return Flowable.just(finalAggregatedTextResponse);
                            }
                          }
                          return Flowable.empty();
                        }));
          });
    } else {
      logger.debug("Sending generateContent request to model {}", effectiveModelName);
      return Flowable.fromFuture(
          apiClient
              .async
              .models
              .generateContent(effectiveModelName, finalContents, config)
              .thenApplyAsync(LlmResponse::create));
    }
  }

  /**
   * Extracts text content from the first part of an LlmResponse, if available.
   *
   * @param llmResponse The LlmResponse to extract text from.
   * @return The text content, or an empty string if not found.
   */
  private String getTextFromLlmResponse(LlmResponse llmResponse) {
    return llmResponse
        .content()
        .flatMap(Content::parts)
        .filter(parts -> !parts.isEmpty())
        .map(parts -> parts.get(0))
        .flatMap(Part::text)
        .orElse("");
  }

  /**
   * Determines if accumulated text should be emitted based on the current LlmResponse. We flush if
   * current response is not a text continuation (e.g., no content, no parts, or the first part is
   * not inline_data, meaning it's something else or just empty, thereby warranting a flush of
   * preceding text).
   *
   * @param currentLlmResponse The current LlmResponse being processed.
   * @return True if accumulated text should be emitted, false otherwise.
   */
  private boolean shouldEmitAccumulatedText(LlmResponse currentLlmResponse) {
    Optional<Content> contentOpt = currentLlmResponse.content();
    if (contentOpt.isEmpty()) {
      return true;
    }

    Optional<List<Part>> partsOpt = contentOpt.get().parts();
    if (partsOpt.isEmpty() || partsOpt.get().isEmpty()) {
      return true;
    }

    // If content and parts are present, and parts list is not empty, we want to yield accumulated
    // text only if `text` is present AND (`not llm_response.content` OR `not
    // llm_response.content.parts` OR `not llm_response.content.parts[0].inline_data`)
    // This means we flush if the first part does NOT have inline_data.
    // If it *has* inline_data, the condition below is false,
    // and we would not flush based on this specific sub-condition.
    Part firstPart = partsOpt.get().get(0);
    return firstPart.inlineData().isEmpty();
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    logger.debug("Establishing Gemini connection.");
    LiveConnectConfig liveConnectConfig = llmRequest.liveConnectConfig();
    String effectiveModelName = llmRequest.model().orElse(model());

    logger.debug("Connecting to model {}", effectiveModelName);
    logger.trace("Connection Config: {}", liveConnectConfig);

    return new GeminiLlmConnection(apiClient, effectiveModelName, liveConnectConfig);
  }

  /** Removes any `Part` that contains only a `thought` from the content list. */
  private List<Content> stripThoughts(List<Content> originalContents) {
    List<Content> updatedContents = new ArrayList<>();
    for (Content content : originalContents) {
      ImmutableList<Part> nonThoughtParts =
          content.parts().orElse(ImmutableList.of()).stream()
              // Keep if thought is not present OR if thought is present but false
              .filter(part -> part.thought().map(isThought -> !isThought).orElse(true))
              .collect(toImmutableList());
      updatedContents.add(content.toBuilder().parts(nonThoughtParts).build());
    }
    return updatedContents;
  }
}
