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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;

/**
 * Converts between ADK and Spring AI message, request, and response formats in both directions.
 *
 * <p>This converter handles the translation between ADK's {@link Content}/{@link Part} and {@link
 * LlmRequest}/{@link LlmResponse} formats and Spring AI's {@link Message} and {@link Prompt}/{@link
 * ChatResponse} formats.
 */
public class MessageConverter {

  private static final Logger logger = LoggerFactory.getLogger(MessageConverter.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

  /**
   * Message-metadata key the Spring AI Google GenAI provider uses to carry Gemini thought
   * signatures. These must be replayed on the model turn of the next request or tool calling fails,
   * so they are preserved across the ADK round-trip. See
   * https://ai.google.dev/gemini-api/docs/thought-signatures.
   */
  private static final String THOUGHT_SIGNATURES_KEY = "thoughtSignatures";

  /** Metadata key the Spring AI Google GenAI provider uses to flag a generation as thought text. */
  private static final String IS_THOUGHT_KEY = "isThought";

  /** Metadata key the Spring AI Anthropic provider sets only on thinking-block generations. */
  private static final String ANTHROPIC_THINKING_SIGNATURE_KEY = "signature";

  /** Metadata key the Spring AI Google GenAI provider uses for the candidate index. */
  private static final String CANDIDATE_INDEX_KEY = "candidateIndex";

  /** Metadata key the Spring AI OpenAI provider uses for the choice index. */
  private static final String OPENAI_CHOICE_INDEX_KEY = "index";

  /** Package of the Spring AI OpenAI provider, which is not a compile-time dependency. */
  private static final String OPENAI_PACKAGE = "org.springframework.ai.openai";

  private final ObjectMapper objectMapper;
  private final ToolConverter toolConverter;
  private final ConfigMapper configMapper;

  public MessageConverter(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    this.toolConverter = new ToolConverter();
    this.configMapper = new ConfigMapper();
  }

  /**
   * Converts an ADK LlmRequest to a Spring AI Prompt.
   *
   * @param llmRequest The ADK request to convert
   * @return A Spring AI Prompt
   */
  public Prompt toLlmPrompt(LlmRequest llmRequest) {
    return toLlmPrompt(llmRequest, null);
  }

  /**
   * Converts an ADK LlmRequest to a Spring AI Prompt, using the target model's own default options
   * as the base for the prompt options.
   *
   * <p>Provider-specific chat models (for example Spring AI OpenAI {@code 2.0.0}) cast {@code
   * Prompt.getOptions()} directly to their own options type (e.g. {@code OpenAiChatOptions}) in
   * {@code createRequest(...)}. Passing provider-neutral options such as {@code
   * DefaultToolCallingChatOptions} therefore triggers a {@link ClassCastException}. To stay
   * compatible with any provider, the prompt options are built on top of the model's own default
   * options (obtained via {@code ChatModel.getOptions()}) so the resulting options keep the
   * concrete type the provider expects, while overlaying the ADK tools and generation config.
   *
   * @param llmRequest The ADK request to convert
   * @param modelDefaultOptions The target model's default options, or {@code null} if unavailable
   * @return A Spring AI Prompt
   */
  public Prompt toLlmPrompt(LlmRequest llmRequest, @Nullable ChatOptions modelDefaultOptions) {
    Objects.requireNonNull(llmRequest, "llmRequest cannot be null");
    List<Message> messages = new ArrayList<>();
    List<String> allSystemMessages = new ArrayList<>(llmRequest.getSystemInstructions());

    List<Message> nonSystemMessages = new ArrayList<>();
    for (Content content : llmRequest.contents()) {
      String role = content.role().orElse("user").toLowerCase(Locale.ROOT);
      if ("system".equals(role)) {
        String systemText = handleSystemContent(content).getText();
        if (systemText != null && !systemText.isEmpty()) {
          allSystemMessages.add(systemText);
        }
      } else {
        nonSystemMessages.addAll(toSpringAiMessages(content));
      }
    }

    if (!allSystemMessages.isEmpty()) {
      String combinedSystemMessage = String.join("\n\n", allSystemMessages);
      messages.add(new SystemMessage(combinedSystemMessage));
    }

    messages.addAll(nonSystemMessages);

    return new Prompt(messages, buildChatOptions(llmRequest, modelDefaultOptions));
  }

  /**
   * Converts a Spring AI {@link Prompt} to an ADK {@link LlmRequest}.
   *
   * <p>System messages are extracted into the request's system instructions, non-system messages
   * are converted to {@link Content} turns, and {@link Prompt#getOptions()} is mapped to {@link
   * GenerateContentConfig}, model name, and ADK tools.
   *
   * @param prompt The Spring AI prompt to convert
   * @return An ADK {@link LlmRequest}
   */
  public LlmRequest toLlmRequest(Prompt prompt) {
    Objects.requireNonNull(prompt, "prompt cannot be null");
    LlmRequest.Builder requestBuilder = LlmRequest.builder();

    List<String> systemInstructions = new ArrayList<>();
    List<Content> contents = new ArrayList<>();

    for (Message message : prompt.getInstructions()) {
      if (message instanceof SystemMessage systemMessage) {
        if (systemMessage.getText() != null && !systemMessage.getText().isEmpty()) {
          systemInstructions.add(systemMessage.getText());
        }
      } else {
        contents.add(toAdkContent(message));
      }
    }
    requestBuilder.contents(ImmutableList.copyOf(contents));

    ChatOptions options = prompt.getOptions();
    if (options != null) {
      if (options.getModel() != null && !options.getModel().isBlank()) {
        requestBuilder.model(options.getModel());
      }
      GenerateContentConfig adkConfig = configMapper.toGenerateContentConfig(options);
      if (options instanceof ToolCallingChatOptions toolOptions
          && !CollectionUtils.isEmpty(toolOptions.getToolCallbacks())) {
        List<BaseTool> adkTools = toolConverter.convertToAdkTools(toolOptions.getToolCallbacks());
        requestBuilder.appendTools(adkTools);
        // Gemini sends only the config, so declare the tools there too, as BaseTool does.
        ImmutableList<FunctionDeclaration> declarations =
            adkTools.stream()
                .flatMap(tool -> tool.declaration().stream())
                .collect(toImmutableList());
        adkConfig =
            adkConfig.toBuilder()
                .tools(ImmutableList.of(Tool.builder().functionDeclarations(declarations).build()))
                .build();
      }
      requestBuilder.config(adkConfig);
    }

    if (!systemInstructions.isEmpty()) {
      requestBuilder.appendInstructions(systemInstructions);
    }

    return requestBuilder.build();
  }

  /**
   * Converts an ADK {@link Content} to one or more Spring AI {@link Message}s.
   *
   * @param content The ADK content to convert
   * @return An immutable list of Spring AI messages
   */
  public ImmutableList<Message> toSpringAiMessages(Content content) {
    Objects.requireNonNull(content, "content cannot be null");
    String role = content.role().orElse("user").toLowerCase(Locale.ROOT);

    return switch (role) {
      case "user" -> handleUserContent(content);
      case "model", "assistant" -> ImmutableList.of(handleAssistantContent(content));
      case "system" -> ImmutableList.of(handleSystemContent(content));
      default -> throw new IllegalStateException("Unexpected role: " + role);
    };
  }

  /**
   * Converts a Spring AI {@link Message} to an ADK {@link Content}.
   *
   * @param message The Spring AI message to convert
   * @return The converted ADK {@link Content}
   */
  public Content toAdkContent(Message message) {
    Objects.requireNonNull(message, "message cannot be null");
    if (message instanceof UserMessage userMessage) {
      return convertUserMessageToContent(userMessage);
    }
    if (message instanceof AssistantMessage assistantMessage) {
      return convertAssistantMessageToContent(assistantMessage);
    }
    if (message instanceof SystemMessage systemMessage) {
      String text = systemMessage.getText() != null ? systemMessage.getText() : "";
      return Content.builder().role("system").parts(Part.fromText(text)).build();
    }
    if (message instanceof ToolResponseMessage toolResponseMessage) {
      return convertToolResponseMessageToContent(toolResponseMessage);
    }
    throw new IllegalArgumentException("Unexpected message type: " + message.getMessageType());
  }

  /**
   * Converts a Spring AI ChatResponse to an ADK LlmResponse.
   *
   * @param chatResponse The Spring AI response to convert
   * @return An ADK LlmResponse
   */
  public LlmResponse toLlmResponse(@Nullable ChatResponse chatResponse) {
    return toLlmResponse(chatResponse, false);
  }

  /**
   * Converts a Spring AI ChatResponse to an ADK LlmResponse with streaming context.
   *
   * @param chatResponse The Spring AI response to convert
   * @param isStreaming Whether this is part of a streaming response
   * @return An ADK LlmResponse
   */
  public LlmResponse toLlmResponse(@Nullable ChatResponse chatResponse, boolean isStreaming) {
    if (chatResponse == null) {
      return LlmResponse.builder().build();
    }

    LlmResponse.Builder responseBuilder = LlmResponse.builder();
    if (!CollectionUtils.isEmpty(chatResponse.getResults())) {
      List<Part> mergedParts = new ArrayList<>();
      String firstFinishReason = null;
      Object firstCandidateIndex =
          chatResponse.getResults().stream()
              .filter(gen -> gen.getOutput() != null)
              .findFirst()
              .map(MessageConverter::candidateIndex)
              .orElse(null);
      for (Generation gen : chatResponse.getResults()) {
        // Providers may split one candidate into several generations; ADK keeps one candidate.
        if (gen.getOutput() != null && !Objects.equals(candidateIndex(gen), firstCandidateIndex)) {
          continue;
        }
        if (gen.getOutput() != null) {
          Content genContent = convertAssistantMessageToContent(gen.getOutput());
          genContent.parts().ifPresent(mergedParts::addAll);
        }
        if (firstFinishReason == null
            && gen.getMetadata() != null
            && gen.getMetadata().getFinishReason() != null
            && !gen.getMetadata().getFinishReason().isBlank()) {
          firstFinishReason = gen.getMetadata().getFinishReason();
        }
      }

      Content content = Content.builder().role("model").parts(mergedParts).build();
      boolean isPartial = isStreaming && isPartialResponse(mergedParts);
      boolean isTurnComplete = !isStreaming || isTurnCompleteFinishReason(firstFinishReason);

      responseBuilder.content(content).partial(isPartial).turnComplete(isTurnComplete);
      if (firstFinishReason != null) {
        FinishReason finishReason = toAdkFinishReason(firstFinishReason);
        responseBuilder.finishReason(finishReason);
        // A non-STOP reason is an error, as for the Gemini model and Python ADK's LiteLLM model.
        if (finishReason.knownEnum() != FinishReason.Known.STOP) {
          responseBuilder
              .errorCode(finishReason)
              .errorMessage(
                  finishReason.knownEnum() == FinishReason.Known.MAX_TOKENS
                      ? "Maximum tokens reached"
                      : "Finished with " + finishReason);
        }
      }
    }

    if (chatResponse.getMetadata() != null) {
      String responseModel = chatResponse.getMetadata().getModel();
      if (responseModel != null && !responseModel.isBlank()) {
        responseBuilder.modelVersion(responseModel);
      }
      Usage springUsage = chatResponse.getMetadata().getUsage();
      if (springUsage != null && !(springUsage instanceof EmptyUsage)) {
        GenerateContentResponseUsageMetadata adkUsage =
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(nullSafeInt(springUsage.getPromptTokens()))
                .candidatesTokenCount(nullSafeInt(springUsage.getCompletionTokens()))
                .totalTokenCount(nullSafeInt(springUsage.getTotalTokens()))
                .build();
        responseBuilder.usageMetadata(adkUsage);
      }
    }
    return responseBuilder.build();
  }

  /**
   * Converts an ADK {@link LlmResponse} to a Spring AI {@link ChatResponse}.
   *
   * @param llmResponse The ADK response to convert
   * @return A Spring AI {@link ChatResponse}
   */
  public ChatResponse toChatResponse(@Nullable LlmResponse llmResponse) {
    if (llmResponse == null) {
      return new ChatResponse(List.of());
    }

    List<Generation> generations = new ArrayList<>();
    if (llmResponse.content().isPresent() || llmResponse.finishReason().isPresent()) {
      AssistantMessage assistantMessage =
          llmResponse
              .content()
              .map(this::handleAssistantContent)
              .orElseGet(() -> new AssistantMessage(""));
      ChatGenerationMetadata generationMetadata =
          llmResponse
              .finishReason()
              .map(
                  reason ->
                      ChatGenerationMetadata.builder().finishReason(reason.toString()).build())
              .orElse(ChatGenerationMetadata.NULL);
      generations.add(new Generation(assistantMessage, generationMetadata));
    }

    ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder();
    boolean hasMetadata = false;
    if (llmResponse.modelVersion().isPresent() && !llmResponse.modelVersion().get().isBlank()) {
      metadataBuilder.model(llmResponse.modelVersion().get());
      hasMetadata = true;
    }
    if (llmResponse.usageMetadata().isPresent()) {
      GenerateContentResponseUsageMetadata usage = llmResponse.usageMetadata().get();
      DefaultUsage springUsage =
          new DefaultUsage(
              usage.promptTokenCount().orElse(0),
              usage.candidatesTokenCount().orElse(0),
              usage.totalTokenCount().orElse(0));
      metadataBuilder.usage(springUsage);
      hasMetadata = true;
    }

    return hasMetadata
        ? new ChatResponse(generations, metadataBuilder.build())
        : new ChatResponse(generations);
  }

  /**
   * Gets tool registry from ADK tools for internal tracking.
   *
   * @param llmRequest The ADK request containing tools
   * @return Map of tool metadata for tracking available tools
   */
  public Map<String, ToolConverter.ToolMetadata> getToolRegistry(LlmRequest llmRequest) {
    return toolConverter.createToolRegistry(llmRequest.tools());
  }

  /**
   * Builds the Spring AI {@link ChatOptions} for a request by overlaying the ADK generation config
   * and tools on top of the model's own default options.
   *
   * @param llmRequest The ADK request being converted
   * @param modelDefaultOptions The target model's default options, or {@code null} if unavailable
   * @return The chat options to attach to the prompt, or {@code null} when there is nothing
   *     ADK-specific to configure (letting the model apply its own defaults)
   */
  private @Nullable ChatOptions buildChatOptions(
      LlmRequest llmRequest, @Nullable ChatOptions modelDefaultOptions) {
    ChatOptions adkChatOptions = configMapper.toSpringAiChatOptions(llmRequest.config());

    List<ToolCallback> toolCallbacks = List.of();
    if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
      toolCallbacks = toolConverter.convertToSpringAiTools(llmRequest.tools());
    }

    boolean hasTools = !toolCallbacks.isEmpty();
    boolean hasAdkConfig = adkChatOptions != null;

    if (!hasTools && !hasAdkConfig) {
      return null;
    }

    if (modelDefaultOptions instanceof ToolCallingChatOptions toolCallingDefaults) {
      ToolCallingChatOptions.Builder<?> optionsBuilder = toolCallingDefaults.mutate();
      if (hasTools) {
        optionsBuilder.toolCallbacks(toolCallbacks);
      }
      applyGenerationConfig(optionsBuilder, adkChatOptions);
      return optionsBuilder.build();
    }

    if (hasTools) {
      if (modelDefaultOptions != null && !(modelDefaultOptions instanceof ToolCallingChatOptions)) {
        // The model's default options are not tool-calling options — typical for delegating or
        // wrapping ChatModels relying on the interface default getOptions() (an empty
        // DefaultChatOptions), or hand-rolled dummy/test models. The prompt will carry
        // provider-neutral ToolCallingChatOptions; provider models that cast
        // Prompt.getOptions() to their own type (e.g. OpenAiChatModel.createRequest) will then
        // fail with a bare ClassCastException whose cause is hard to spot. Warn so the cause is
        // one grep away; dummy models that ignore options keep working.
        logger.warn(
            "Tool callbacks were attached to provider-neutral ToolCallingChatOptions because the"
                + " model's default options are {} (not ToolCallingChatOptions). Provider models"
                + " such as OpenAiChatModel will fail with a ClassCastException on this prompt;"
                + " if the model is a wrapper, make getOptions() delegate to the underlying"
                + " provider model's options (e.g. OpenAiChatOptions).",
            modelDefaultOptions.getClass().getName());
      }
      ToolCallingChatOptions.Builder<?> optionsBuilder = ToolCallingChatOptions.builder();
      optionsBuilder.toolCallbacks(toolCallbacks);
      if (modelDefaultOptions != null && modelDefaultOptions.getModel() != null) {
        optionsBuilder.model(modelDefaultOptions.getModel());
      }
      applyGenerationConfig(optionsBuilder, adkChatOptions);
      return optionsBuilder.build();
    }
    return adkChatOptions;
  }

  /** Copies the non-null generation parameters from {@code source} onto {@code builder}. */
  private void applyGenerationConfig(ChatOptions.Builder<?> builder, @Nullable ChatOptions source) {
    if (source == null) {
      return;
    }
    if (source.getTemperature() != null) {
      builder.temperature(source.getTemperature());
    }
    if (source.getMaxTokens() != null) {
      builder.maxTokens(source.getMaxTokens());
    }
    if (source.getTopP() != null) {
      builder.topP(source.getTopP());
    }
    if (source.getTopK() != null) {
      builder.topK(source.getTopK());
    }
    if (source.getStopSequences() != null) {
      builder.stopSequences(source.getStopSequences());
    }
    if (source.getModel() != null) {
      builder.model(source.getModel());
    }
    if (source.getFrequencyPenalty() != null) {
      builder.frequencyPenalty(source.getFrequencyPenalty());
    }
    if (source.getPresencePenalty() != null) {
      builder.presencePenalty(source.getPresencePenalty());
    }
    if (source instanceof StructuredOutputChatOptions structuredSource
        && structuredSource.getOutputSchema() != null
        && builder instanceof StructuredOutputChatOptions.Builder<?> structuredBuilder) {
      String outputSchema = structuredSource.getOutputSchema();
      // Spring AI's OpenAI provider sends the schema in strict mode, which requires this shape.
      if (builder.getClass().getPackageName().equals(OPENAI_PACKAGE)) {
        outputSchema = ConfigMapper.toOpenAiStrictSchema(outputSchema);
      }
      structuredBuilder.outputSchema(outputSchema);
    }
  }

  private ImmutableList<Message> handleUserContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
    List<Media> mediaList = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      } else if (part.functionResponse().isPresent()) {
        FunctionResponse functionResponse = part.functionResponse().get();
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(
                functionResponse.id().orElse(""),
                functionResponse.name().orElse(""),
                toJson(functionResponse.response().orElse(Map.of()))));
      } else {
        partToMedia(part).ifPresent(mediaList::add);
      }
    }

    ImmutableList.Builder<Message> messages = ImmutableList.builder();
    String text = textBuilder.toString();
    // Emit a UserMessage for any text/media, or for an otherwise-empty turn; but when the turn only
    // carries function responses, emit just the ToolResponseMessage so the request does not end on
    // the model's tool-call turn (which the backend rejects).
    if (!text.isEmpty() || !mediaList.isEmpty() || toolResponses.isEmpty()) {
      messages.add(UserMessage.builder().text(text).media(mediaList).build());
    }
    if (!toolResponses.isEmpty()) {
      messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
    }

    return messages.build();
  }

  private AssistantMessage handleAssistantContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    List<byte[]> thoughtSignatures = new ArrayList<>();
    List<Media> mediaList = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        if (!part.thought().orElse(false)) {
          textBuilder.append(part.text().get());
        }
      } else if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        toolCalls.add(
            new AssistantMessage.ToolCall(
                functionCall.id().orElse(""),
                "function",
                functionCall
                    .name()
                    .orElseThrow(() -> new IllegalStateException("Function call name is missing")),
                toJson(functionCall.args().orElse(Map.of()))));
        part.thoughtSignature().ifPresent(thoughtSignatures::add);
      } else {
        partToMedia(part).ifPresent(mediaList::add);
      }
    }

    String text = textBuilder.toString();
    if (toolCalls.isEmpty() && mediaList.isEmpty()) {
      return new AssistantMessage(text);
    }
    Map<String, Object> properties =
        thoughtSignatures.isEmpty() ? Map.of() : Map.of(THOUGHT_SIGNATURES_KEY, thoughtSignatures);
    return AssistantMessage.builder()
        .content(text)
        .properties(properties)
        .toolCalls(toolCalls)
        .media(mediaList)
        .build();
  }

  private SystemMessage handleSystemContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    for (Part part : content.parts().orElse(List.of())) {
      part.text().ifPresent(textBuilder::append);
    }
    return new SystemMessage(textBuilder.toString());
  }

  private Optional<Media> partToMedia(Part part) {
    if (part.inlineData().isPresent()) {
      Blob blob = part.inlineData().get();
      if (blob.mimeType().isPresent() && blob.data().isPresent()) {
        try {
          MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
          ByteArrayResource resource = new ByteArrayResource(blob.data().get());
          return Optional.of(new Media(mimeType, resource));
        } catch (IllegalArgumentException e) {
          logger.warn("Failed to process inline media part: {}", e.getClass().getName());
        }
      }
    } else if (part.fileData().isPresent()) {
      FileData fileData = part.fileData().get();
      if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
        try {
          MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
          URI uri = URI.create(fileData.fileUri().get());
          return Optional.of(new Media(mimeType, uri));
        } catch (IllegalArgumentException e) {
          logger.warn("Failed to process file media part: {}", e.getClass().getName());
        }
      }
    }
    return Optional.empty();
  }

  private Optional<Part> mediaToPart(@Nullable Media media) {
    if (media == null || media.getMimeType() == null) {
      return Optional.empty();
    }
    String mimeType = media.getMimeType().toString();
    Object data = media.getData();
    if (data instanceof byte[] bytes) {
      return Optional.of(Part.fromBytes(bytes, mimeType));
    }
    if (data instanceof URI || data instanceof URL || data instanceof String) {
      return Optional.of(Part.fromUri(data.toString(), mimeType));
    }
    logger.warn("Unsupported Spring AI Media data type: {}", data.getClass().getName());
    return Optional.empty();
  }

  private Content convertUserMessageToContent(UserMessage userMessage) {
    List<Part> parts = new ArrayList<>();
    if (userMessage.getText() != null && !userMessage.getText().isEmpty()) {
      parts.add(Part.fromText(userMessage.getText()));
    }
    if (userMessage.getMedia() != null) {
      for (Media media : userMessage.getMedia()) {
        mediaToPart(media).ifPresent(parts::add);
      }
    }
    return Content.builder().role("user").parts(parts).build();
  }

  private Content convertToolResponseMessageToContent(ToolResponseMessage toolResponseMessage) {
    List<Part> parts = new ArrayList<>();
    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
      FunctionResponse.Builder responseBuilder =
          FunctionResponse.builder()
              .name(response.name())
              .response(ToolConverter.parseToolResult(response.responseData()));
      if (response.id() != null && !response.id().isEmpty()) {
        responseBuilder.id(response.id());
      }
      parts.add(Part.builder().functionResponse(responseBuilder.build()).build());
    }
    return Content.builder().role("user").parts(parts).build();
  }

  /**
   * Returns the candidate a generation belongs to, from the Spring AI Google GenAI ({@code
   * candidateIndex}) or OpenAI ({@code index}) message metadata, or null if neither is set.
   */
  private static @Nullable Object candidateIndex(Generation generation) {
    Map<String, Object> metadata =
        generation.getOutput() != null ? generation.getOutput().getMetadata() : null;
    if (metadata == null) {
      return null;
    }
    return metadata.containsKey(CANDIDATE_INDEX_KEY)
        ? metadata.get(CANDIDATE_INDEX_KEY)
        : metadata.get(OPENAI_CHOICE_INDEX_KEY);
  }

  private int nullSafeInt(@Nullable Integer value) {
    return value != null ? value.intValue() : 0;
  }

  /**
   * Determines if the merged parts of a streamed candidate are partial: they carry no function call
   * and their text does not end with sentence punctuation.
   */
  private static boolean isPartialResponse(List<Part> parts) {
    if (parts.stream().anyMatch(part -> part.functionCall().isPresent())) {
      return false;
    }
    String text =
        parts.stream().map(part -> part.text().orElse("")).collect(Collectors.joining()).trim();
    return !text.isEmpty() && !text.endsWith(".") && !text.endsWith("!") && !text.endsWith("?");
  }

  /** Determines if a candidate's finish reason indicates the turn is complete. */
  private boolean isTurnCompleteFinishReason(@Nullable String finishReason) {
    if (finishReason == null) {
      return true;
    }
    FinishReason.Known known = toAdkFinishReason(finishReason).knownEnum();
    return known == FinishReason.Known.STOP
        || known == FinishReason.Known.MAX_TOKENS
        || known == FinishReason.Known.SAFETY
        || known == FinishReason.Known.RECITATION;
  }

  private FinishReason toAdkFinishReason(String finishReason) {
    return switch (finishReason.toLowerCase(Locale.ROOT)) {
      case "stop",
          "tool_calls",
          "tool_use",
          "end_turn",
          "stop_sequence",
          "pause_turn",
          "function_call" ->
          new FinishReason(FinishReason.Known.STOP);
      case "length", "max_tokens" -> new FinishReason(FinishReason.Known.MAX_TOKENS);
      case "content_filter", "safety" -> new FinishReason(FinishReason.Known.SAFETY);
      case "recitation" -> new FinishReason(FinishReason.Known.RECITATION);
      default -> {
        FinishReason candidate = new FinishReason(finishReason.toUpperCase(Locale.ROOT));
        yield candidate.knownEnum() != FinishReason.Known.FINISH_REASON_UNSPECIFIED
            ? candidate
            : new FinishReason(FinishReason.Known.OTHER);
      }
    };
  }

  private Content convertAssistantMessageToContent(AssistantMessage assistantMessage) {
    List<Part> parts = new ArrayList<>();

    if (assistantMessage.getText() != null && !assistantMessage.getText().isEmpty()) {
      Map<String, Object> metadata = assistantMessage.getMetadata();
      boolean isThought =
          metadata != null
              && (Boolean.TRUE.equals(metadata.get(IS_THOUGHT_KEY))
                  || metadata.containsKey(ANTHROPIC_THINKING_SIGNATURE_KEY));
      Part textPart =
          isThought
              ? Part.builder().text(assistantMessage.getText()).thought(true).build()
              : Part.fromText(assistantMessage.getText());
      parts.add(textPart);
    }

    if (assistantMessage.getMedia() != null) {
      for (Media media : assistantMessage.getMedia()) {
        mediaToPart(media).ifPresent(parts::add);
      }
    }

    List<byte[]> thoughtSignatures = extractThoughtSignatures(assistantMessage);
    int functionCallIndex = 0;

    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
      if ("function".equals(toolCall.type())) {
        Map<String, Object> args = parseToolCallArguments(toolCall.arguments());

        FunctionCall.Builder functionCallBuilder =
            FunctionCall.builder().name(toolCall.name()).args(args);
        if (toolCall.id() != null && !toolCall.id().isEmpty()) {
          functionCallBuilder.id(toolCall.id());
        }
        FunctionCall functionCall = functionCallBuilder.build();

        Part.Builder partBuilder = Part.builder().functionCall(functionCall);
        if (functionCallIndex < thoughtSignatures.size()) {
          partBuilder.thoughtSignature(thoughtSignatures.get(functionCallIndex));
        }
        parts.add(partBuilder.build());
        functionCallIndex++;
      }
    }

    return Content.builder().role("model").parts(parts).build();
  }

  private Map<String, Object> parseToolCallArguments(@Nullable String rawArguments) {
    if (rawArguments == null || rawArguments.isBlank()) {
      return ImmutableMap.of();
    }
    try {
      Map<String, Object> args =
          objectMapper
              .readerFor(MAP_TYPE_REFERENCE)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(rawArguments);
      return args != null ? args : ImmutableMap.of();
    } catch (JsonProcessingException e) {
      throw MessageConversionException.jsonParsingFailed("tool call arguments", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<byte[]> extractThoughtSignatures(AssistantMessage assistantMessage) {
    Map<String, Object> metadata = assistantMessage.getMetadata();
    if (metadata == null) {
      return List.of();
    }
    Object signatures = metadata.get(THOUGHT_SIGNATURES_KEY);
    return (signatures instanceof List) ? (List<byte[]>) signatures : List.of();
  }

  private String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw MessageConversionException.jsonParsingFailed("object serialization", e);
    }
  }
}
