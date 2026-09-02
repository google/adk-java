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
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

/**
 * Spring AI implementation of BaseLlm that wraps Spring AI ChatModel and StreamingChatModel.
 *
 * <p>This adapter allows Spring AI models to be used within the ADK framework by converting between
 * ADK's LlmRequest/LlmResponse format and Spring AI's Prompt/ChatResponse format.
 */
public class SpringAI extends BaseLlm {

  private static final int MAX_TOOL_CALL_ITERATIONS = 100;

  private final ChatModel chatModel;
  private final StreamingChatModel streamingChatModel;
  private final ObjectMapper objectMapper;
  private final MessageConverter messageConverter;
  private final SpringAIObservabilityHandler observabilityHandler;
  private final ToolExecutionMode toolExecutionMode;
  private final ToolCallingManager toolCallingManager;
  private final int maxToolCallIterations;

  public SpringAI(ChatModel chatModel) {
    this(chatModel, extractModelName(chatModel));
  }

  public SpringAI(ChatModel chatModel, String modelName) {
    this(
        chatModel,
        chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null,
        modelName,
        createDefaultObservabilityConfig(),
        ToolExecutionMode.ADK_MANAGED,
        null,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      ChatModel chatModel,
      String modelName,
      ToolExecutionMode toolExecutionMode,
      AdkToolContextResolver toolContextResolver) {
    this(
        chatModel,
        chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null,
        modelName,
        createDefaultObservabilityConfig(),
        toolExecutionMode,
        toolContextResolver,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(StreamingChatModel streamingChatModel) {
    this(streamingChatModel, extractModelName(streamingChatModel));
  }

  public SpringAI(StreamingChatModel streamingChatModel, String modelName) {
    this(
        streamingChatModel instanceof ChatModel ? (ChatModel) streamingChatModel : null,
        streamingChatModel,
        modelName,
        createDefaultObservabilityConfig(),
        ToolExecutionMode.ADK_MANAGED,
        null,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      StreamingChatModel streamingChatModel,
      String modelName,
      ToolExecutionMode toolExecutionMode,
      AdkToolContextResolver toolContextResolver) {
    this(
        streamingChatModel instanceof ChatModel ? (ChatModel) streamingChatModel : null,
        streamingChatModel,
        modelName,
        createDefaultObservabilityConfig(),
        toolExecutionMode,
        toolContextResolver,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
    this(
        chatModel,
        streamingChatModel,
        modelName,
        createDefaultObservabilityConfig(),
        ToolExecutionMode.ADK_MANAGED,
        null,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      ChatModel chatModel,
      StreamingChatModel streamingChatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig) {
    this(
        chatModel,
        streamingChatModel,
        modelName,
        observabilityConfig,
        ToolExecutionMode.ADK_MANAGED,
        null,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      ChatModel chatModel,
      StreamingChatModel streamingChatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig,
      ToolExecutionMode toolExecutionMode,
      AdkToolContextResolver toolContextResolver) {
    this(
        chatModel,
        streamingChatModel,
        modelName,
        observabilityConfig,
        toolExecutionMode,
        toolContextResolver,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
    this(
        chatModel,
        chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null,
        modelName,
        observabilityConfig,
        ToolExecutionMode.ADK_MANAGED,
        null,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      ChatModel chatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig,
      ToolExecutionMode toolExecutionMode,
      AdkToolContextResolver toolContextResolver) {
    this(
        chatModel,
        chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null,
        modelName,
        observabilityConfig,
        toolExecutionMode,
        toolContextResolver,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      StreamingChatModel streamingChatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig) {
    this(
        streamingChatModel instanceof ChatModel ? (ChatModel) streamingChatModel : null,
        streamingChatModel,
        modelName,
        observabilityConfig,
        ToolExecutionMode.ADK_MANAGED,
        null,
        MAX_TOOL_CALL_ITERATIONS);
  }

  public SpringAI(
      StreamingChatModel streamingChatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig,
      ToolExecutionMode toolExecutionMode,
      AdkToolContextResolver toolContextResolver) {
    this(
        streamingChatModel instanceof ChatModel ? (ChatModel) streamingChatModel : null,
        streamingChatModel,
        modelName,
        observabilityConfig,
        toolExecutionMode,
        toolContextResolver,
        MAX_TOOL_CALL_ITERATIONS);
  }

  private SpringAI(
      ChatModel chatModel,
      StreamingChatModel streamingChatModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig,
      ToolExecutionMode toolExecutionMode,
      AdkToolContextResolver toolContextResolver,
      int maxToolCallIterations) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    if (chatModel == null && streamingChatModel == null) {
      throw new NullPointerException("At least one chat model must be configured");
    }
    this.chatModel = chatModel;
    this.streamingChatModel = streamingChatModel;
    this.objectMapper = new ObjectMapper();
    ToolConverter toolConverter =
        new ToolConverter(objectMapper, toolExecutionMode, toolContextResolver);
    this.messageConverter = new MessageConverter(objectMapper, toolConverter);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(
            Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    this.toolExecutionMode = toolExecutionMode;
    this.maxToolCallIterations = maxToolCallIterations;
    this.toolCallingManager =
        ToolCallingManager.builder().maxTotalToolCalls(maxToolCallIterations).build();
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

      ChatResponse chatResponse = callChatModel(prompt);
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
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest, resolveDefaultOptions());
            observabilityHandler.logRequest(prompt.toString(), model());

            Flux<ChatResponse> responseFlux = streamChatModel(prompt, 0);

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

  private ChatResponse callChatModel(Prompt initialPrompt) {
    Prompt prompt = initialPrompt;
    ChatResponse chatResponse = chatModel.call(prompt);
    if (toolExecutionMode == ToolExecutionMode.ADK_MANAGED) {
      return chatResponse;
    }

    int iteration = 0;
    while (chatResponse.hasToolCalls()) {
      if (iteration++ >= maxToolCallIterations) {
        throw new IllegalStateException(
            "Spring AI tool execution exceeded " + maxToolCallIterations + " iterations");
      }
      ToolExecutionResult toolExecutionResult =
          toolCallingManager.executeToolCalls(prompt, chatResponse);
      if (toolExecutionResult.returnDirect()) {
        return new ChatResponse(ToolExecutionResult.buildGenerations(toolExecutionResult));
      }
      prompt = new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions());
      chatResponse = chatModel.call(prompt);
    }
    return chatResponse;
  }

  private Flux<ChatResponse> streamChatModel(Prompt prompt, int iteration) {
    if (toolExecutionMode == ToolExecutionMode.ADK_MANAGED) {
      return streamingChatModel.stream(prompt);
    }
    if (iteration >= maxToolCallIterations) {
      return Flux.error(
          new IllegalStateException(
              "Spring AI tool execution exceeded " + maxToolCallIterations + " iterations"));
    }

    AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
    return new MessageAggregator()
        .aggregate(streamingChatModel.stream(prompt), aggregatedResponse::set)
        .collectList()
        .flatMapMany(
            chunks -> {
              ChatResponse chatResponse = aggregatedResponse.get();
              if (chatResponse == null || !chatResponse.hasToolCalls()) {
                return Flux.fromIterable(chunks);
              }

              ToolExecutionResult toolExecutionResult =
                  toolCallingManager.executeToolCalls(prompt, chatResponse);
              if (toolExecutionResult.returnDirect()) {
                return Flux.just(
                    new ChatResponse(ToolExecutionResult.buildGenerations(toolExecutionResult)));
              }
              Prompt nextPrompt =
                  new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions());
              return streamChatModel(nextPrompt, iteration + 1);
            });
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

  private static SpringAIProperties.Observability createDefaultObservabilityConfig() {
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
