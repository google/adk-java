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
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

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
      Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
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
    SpringAIObservabilityHandler.RequestContext context =
        observabilityHandler.startRequest(model(), "streaming");

    return Flowable.create(
        emitter -> {
          try {
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            observabilityHandler.logRequest(prompt.toString(), model());

            Flux<ChatResponse> responseFlux = streamingChatModel.stream(prompt);

            responseFlux
                .doOnError(
                    error -> {
                      observabilityHandler.recordError(context, error);
                      SpringAIErrorMapper.MappedError mappedError =
                          SpringAIErrorMapper.mapError(error);
                      emitter.onError(
                          new RuntimeException(mappedError.getNormalizedMessage(), error));
                    })
                .subscribe(
                    chatResponse -> {
                      try {
                        // Use enhanced streaming-aware conversion
                        LlmResponse llmResponse =
                            messageConverter.toLlmResponse(chatResponse, true);
                        emitter.onNext(llmResponse);
                      } catch (Exception e) {
                        observabilityHandler.recordError(context, e);
                        SpringAIErrorMapper.MappedError mappedError =
                            SpringAIErrorMapper.mapError(e);
                        emitter.onError(
                            new RuntimeException(mappedError.getNormalizedMessage(), e));
                      }
                    },
                    error -> {
                      observabilityHandler.recordError(context, error);
                      SpringAIErrorMapper.MappedError mappedError =
                          SpringAIErrorMapper.mapError(error);
                      emitter.onError(
                          new RuntimeException(mappedError.getNormalizedMessage(), error));
                    },
                    () -> {
                      // Record success for streaming completion
                      observabilityHandler.recordSuccess(context, 0, 0, 0);
                      emitter.onComplete();
                    });
          } catch (Exception e) {
            observabilityHandler.recordError(context, e);
            SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);
            emitter.onError(new RuntimeException(mappedError.getNormalizedMessage(), e));
          }
        },
        BackpressureStrategy.BUFFER);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        "Live connection is not supported for Spring AI models.");
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
