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
package com.google.adk.models.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.FunctionCallIds;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Spring AI implementation of BaseLlm that wraps Spring AI ChatModel and StreamingChatModel.
 *
 * <p>This adapter allows Spring AI models to be used within the ADK framework by converting between
 * ADK's LlmRequest/LlmResponse format and Spring AI's Prompt/ChatResponse format.
 */
public class SpringAI extends BaseLlm {

  private final ChatModel chatModel;
  private final StreamingChatModel streamingChatModel;
  private final ObjectMapper objectMapper;
  private final MessageConverter messageConverter;
  private final SpringAIObservabilityHandler observabilityHandler;

  public SpringAI(ChatModel chatModel) {
    super(extractModelName(chatModel));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel =
        (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
  }

  public SpringAI(ChatModel chatModel, String modelName) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel =
        (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
  }

  public SpringAI(StreamingChatModel streamingChatModel) {
    super(extractModelName(streamingChatModel));
    this.chatModel =
        (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
  }

  public SpringAI(StreamingChatModel streamingChatModel, String modelName) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    this.chatModel =
        (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
  }

  public SpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
  }

  public SpringAI(
      ChatModel chatModel,
      StreamingChatModel streamingChatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(
            Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
  }

  public SpringAI(
      ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel =
        (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(
            Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
  }

  public SpringAI(
      StreamingChatModel streamingChatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    this.chatModel =
        (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
    this.messageConverter = new MessageConverter(objectMapper);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(
            Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      if (this.streamingChatModel == null) {
        return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
      }

      return generateStreamingContent(llmRequest);
    } else {
      if (this.chatModel == null) {
        return Flowable.error(new IllegalStateException("ChatModel is not configured"));
      }

      return generateContent(llmRequest);
    }
  }

  private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
    SpringAIObservabilityHandler.RequestContext context =
        observabilityHandler.startRequest(model(), "chat");

    try {
      Prompt prompt = messageConverter.toLlmPrompt(llmRequest, resolveDefaultOptions());
      observabilityHandler.logRequest(prompt.toString(), model());

      ChatResponse chatResponse = chatModel.call(prompt);
      LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

      observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());

      // Extract token counts if available
      int totalTokens = extractTokenCount(chatResponse);
      int inputTokens = extractInputTokenCount(chatResponse);
      int outputTokens = extractOutputTokenCount(chatResponse);

      observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);
      return Flowable.just(llmResponse);
    } catch (Exception e) {
      observabilityHandler.recordError(context, e);
      SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);

      return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e));
    }
  }

  private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
    return Flowable.defer(
        () -> {
          SpringAIObservabilityHandler.RequestContext context =
              observabilityHandler.startRequest(model(), "streaming");
          // Deferred so a failure building the prompt also reaches onErrorResumeNext.
          return Flowable.defer(() -> streamResponses(llmRequest))
              .doOnComplete(() -> observabilityHandler.recordSuccess(context, 0, 0, 0))
              .onErrorResumeNext(
                  error -> {
                    observabilityHandler.recordError(context, error);
                    SpringAIErrorMapper.MappedError mappedError =
                        SpringAIErrorMapper.mapError(error);
                    return Flowable.error(
                        new RuntimeException(mappedError.getNormalizedMessage(), error));
                  });
        });
  }

  /**
   * Emits each chunk that has content as a partial response and, if the model streamed anything,
   * then one aggregated response, as {@code Gemini} does. ADK saves only the aggregated response to
   * the session and runs its function calls.
   */
  private Flowable<LlmResponse> streamResponses(LlmRequest llmRequest) {
    Prompt prompt = messageConverter.toLlmPrompt(llmRequest, resolveDefaultOptions());
    observabilityHandler.logRequest(prompt.toString(), model());
    StreamAggregation aggregation = new StreamAggregation();
    return Flowable.fromPublisher(streamingChatModel.stream(prompt))
        .concatMap(aggregation::processChunk)
        .concatWith(Flowable.defer(aggregation::aggregatedResponse));
  }

  /** The state of one stream, used to build its aggregated response. */
  private final class StreamAggregation {
    // Parts in stream order. Consecutive text, or consecutive thought text, merges into one part;
    // function calls, media and other parts are kept as they arrived.
    private final List<Part> parts = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private boolean textIsThought;
    private @Nullable GenerateContentResponseUsageMetadata usage;
    private @Nullable String modelVersion;
    private @Nullable LlmResponse finishingChunk;
    private boolean receivedChunk;

    private Flowable<LlmResponse> processChunk(ChatResponse chatResponse) {
      receivedChunk = true;
      LlmResponse chunk = withFunctionCallIds(messageConverter.toLlmResponse(chatResponse));
      chunk.usageMetadata().ifPresent(chunkUsage -> usage = chunkUsage);
      chunk.modelVersion().ifPresent(version -> modelVersion = version);
      if (chunk.finishReason().isPresent()) {
        finishingChunk = chunk;
      }
      List<Part> chunkParts = chunk.content().flatMap(Content::parts).orElse(List.of());
      if (chunkParts.isEmpty()) {
        return Flowable.empty();
      }
      for (Part part : chunkParts) {
        if (part.text().isPresent()) {
          boolean isThought = part.thought().orElse(false);
          if (isThought != textIsThought) {
            flushText();
          }
          textIsThought = isThought;
          text.append(part.text().get());
        } else {
          flushText();
          parts.add(part);
        }
      }
      // As in Gemini, only the aggregated response reports an error.
      return Flowable.just(
          chunk.toBuilder()
              .partial(true)
              .turnComplete(null)
              .errorCode(null)
              .errorMessage(null)
              .build());
    }

    private void flushText() {
      if (text.isEmpty()) {
        return;
      }
      Part.Builder part = Part.builder().text(text.toString());
      if (textIsThought) {
        part.thought(true);
      }
      parts.add(part.build());
      text.setLength(0);
    }

    private Flowable<LlmResponse> aggregatedResponse() {
      if (!receivedChunk) {
        return Flowable.empty();
      }
      flushText();
      LlmResponse.Builder aggregated =
          LlmResponse.builder().partial(false).turnComplete(true).modelVersion(modelVersion);
      // A stream without content still ends with a response, as Gemini's does.
      if (!parts.isEmpty()) {
        aggregated.content(Content.builder().role("model").parts(parts).build());
      }
      if (usage != null) {
        aggregated.usageMetadata(usage);
      }
      // Report how the stream ended, from the last chunk that said so, as ADK Python does.
      if (finishingChunk != null) {
        aggregated
            .finishReason(finishingChunk.finishReason().orElse(null))
            .errorCode(finishingChunk.errorCode().orElse(null))
            .errorMessage(finishingChunk.errorMessage().orElse(null));
      }
      return Flowable.just(aggregated.build());
    }
  }

  /**
   * Gives each function call without an id a client-side one, so the partial and aggregated
   * responses carrying it share the same id.
   */
  private static LlmResponse withFunctionCallIds(LlmResponse response) {
    if (response.content().isEmpty()) {
      return response;
    }
    Content content = response.content().get();
    List<Part> parts = content.parts().orElse(List.of());
    if (parts.stream()
        .noneMatch(part -> part.functionCall().filter(SpringAI::lacksId).isPresent())) {
      return response;
    }
    return response.toBuilder()
        .content(
            content.toBuilder()
                .parts(parts.stream().map(SpringAI::withFunctionCallId).toList())
                .build())
        .build();
  }

  private static Part withFunctionCallId(Part part) {
    return part.functionCall()
        .filter(SpringAI::lacksId)
        .map(
            call ->
                part.toBuilder()
                    .functionCall(
                        call.toBuilder().id(FunctionCallIds.generateClientFunctionCallId()).build())
                    .build())
        .orElse(part);
  }

  private static boolean lacksId(FunctionCall call) {
    return call.id().map(String::isEmpty).orElse(true);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        "Live connection is not supported for Spring AI models.");
  }

  /**
   * Returns the underlying model's own default {@link ChatOptions}, or {@code null} if they cannot
   * be determined.
   *
   * <p>These are used as the base for the prompt options so provider-specific models (e.g. Spring
   * AI OpenAI) receive options of the concrete type they expect, avoiding a {@link
   * ClassCastException} when they cast {@code Prompt.getOptions()} to their provider-specific
   * options type.
   */
  private ChatOptions resolveDefaultOptions() {
    if (chatModel != null) {
      return chatModel.getOptions();
    }
    if (streamingChatModel instanceof ChatModel) {
      return ((ChatModel) streamingChatModel).getOptions();
    }
    return null;
  }

  private static String extractModelName(Object model) {
    // Spring AI models may not always have a straightforward way to get model name
    // This is a fallback that can be overridden by providing explicit model name
    String className = model.getClass().getSimpleName();
    return className.toLowerCase().replace("chatmodel", "").replace("model", "");
  }

  private SpringAIProperties.Observability createDefaultObservabilityConfig() {
    SpringAIProperties.Observability config = new SpringAIProperties.Observability();
    config.setEnabled(true);
    config.setMetricsEnabled(true);
    config.setIncludeContent(false);
    return config;
  }

  private int extractTokenCount(ChatResponse chatResponse) {
    // Spring AI may include usage metadata in the response
    // This is a simplified implementation - actual token counts depend on provider
    try {
      if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
        return chatResponse.getMetadata().getUsage().getTotalTokens();
      }
    } catch (Exception e) {
      // Ignore errors in token extraction
    }
    return 0;
  }

  private int extractInputTokenCount(ChatResponse chatResponse) {
    try {
      if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
        return chatResponse.getMetadata().getUsage().getPromptTokens();
      }
    } catch (Exception e) {
      // Ignore errors in token extraction
    }
    return 0;
  }

  private int extractOutputTokenCount(ChatResponse chatResponse) {
    try {
      if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
        return chatResponse.getMetadata().getUsage().getCompletionTokens();
      }
    } catch (Exception e) {
      // Ignore errors in token extraction
    }
    return 0;
  }

  private String extractTextFromResponse(LlmResponse response) {
    if (response.content().isPresent() && response.content().get().parts().isPresent()) {
      return response.content().get().parts().get().stream()
          .map(part -> part.text().orElse(""))
          .filter(text -> text != null && !text.isEmpty())
          .findFirst()
          .orElse("");
    }
    return "";
  }
}
