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
package com.google.adk.models.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.ToolConfig;
import com.google.genai.types.Type;
import dev.langchain4j.Experimental;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.data.video.Video;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Experimental
public class LangChain4j extends BaseLlm {

  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

  private final ChatModel chatModel;
  private final StreamingChatModel streamingChatModel;
  private final ObjectMapper objectMapper;

  public LangChain4j(ChatModel chatModel) {
    super(
        Objects.requireNonNull(
            chatModel.defaultRequestParameters().modelName(), "chat model name cannot be null"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel = null;
    this.objectMapper = new ObjectMapper();
  }

  public LangChain4j(ChatModel chatModel, String modelName) {
    super(Objects.requireNonNull(modelName, "chat model name cannot be null"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel = null;
    this.objectMapper = new ObjectMapper();
  }

  public LangChain4j(StreamingChatModel streamingChatModel) {
    super(
        Objects.requireNonNull(
            streamingChatModel.defaultRequestParameters().modelName(),
            "streaming chat model name cannot be null"));
    this.chatModel = null;
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
  }

  public LangChain4j(StreamingChatModel streamingChatModel, String modelName) {
    super(Objects.requireNonNull(modelName, "streaming chat model name cannot be null"));
    this.chatModel = null;
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
  }

  public LangChain4j(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
    super(Objects.requireNonNull(modelName, "model name cannot be null"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.streamingChatModel =
        Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      if (this.streamingChatModel == null) {
        return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
      }

      ChatRequest chatRequest = toChatRequest(llmRequest);

      return Flowable.create(
          emitter -> {
            streamingChatModel.chat(
                chatRequest,
                new StreamingChatResponseHandler() {
                  @Override
                  public void onPartialResponse(String s) {
                    emitter.onNext(
                        LlmResponse.builder().content(Content.fromParts(Part.fromText(s))).build());
                  }

                  @Override
                  public void onCompleteResponse(ChatResponse chatResponse) {
                    if (chatResponse.aiMessage().hasToolExecutionRequests()) {
                      AiMessage aiMessage = chatResponse.aiMessage();
                      toParts(aiMessage).stream()
                          .map(Part::functionCall)
                          .forEach(
                              functionCall -> {
                                functionCall.ifPresent(
                                    function -> {
                                      emitter.onNext(
                                          LlmResponse.builder()
                                              .content(
                                                  Content.fromParts(
                                                      Part.fromFunctionCall(
                                                          function.name().orElse(""),
                                                          function.args().orElse(Map.of()))))
                                              .build());
                                    });
                              });
                    }
                    emitter.onComplete();
                  }

                  @Override
                  public void onError(Throwable throwable) {
                    emitter.onError(throwable);
                  }
                });
          },
          BackpressureStrategy.BUFFER);
    } else {
      if (this.chatModel == null) {
        return Flowable.error(new IllegalStateException("ChatModel is not configured"));
      }

      ChatRequest chatRequest = toChatRequest(llmRequest);
      ChatResponse chatResponse = chatModel.chat(chatRequest);
      LlmResponse llmResponse = toLlmResponse(chatResponse);

      return Flowable.just(llmResponse);
    }
  }

  private ChatRequest toChatRequest(LlmRequest llmRequest) {
    ChatRequest.Builder requestBuilder = ChatRequest.builder();

    List<ToolSpecification> toolSpecifications = toToolSpecifications(llmRequest);
    requestBuilder.toolSpecifications(toolSpecifications);

    if (llmRequest.config().isPresent()) {
      GenerateContentConfig generateContentConfig = llmRequest.config().get();

      generateContentConfig
          .temperature()
          .ifPresent(temp -> requestBuilder.temperature(temp.doubleValue()));
      generateContentConfig.topP().ifPresent(topP -> requestBuilder.topP(topP.doubleValue()));
      generateContentConfig.topK().ifPresent(topK -> requestBuilder.topK(topK.intValue()));
      generateContentConfig.maxOutputTokens().ifPresent(requestBuilder::maxOutputTokens);
      generateContentConfig.stopSequences().ifPresent(requestBuilder::stopSequences);
      generateContentConfig
          .frequencyPenalty()
          .ifPresent(freqPenalty -> requestBuilder.frequencyPenalty(freqPenalty.doubleValue()));
      generateContentConfig
          .presencePenalty()
          .ifPresent(presPenalty -> requestBuilder.presencePenalty(presPenalty.doubleValue()));

      if (generateContentConfig.toolConfig().isPresent()) {
        ToolConfig toolConfig = generateContentConfig.toolConfig().get();
        toolConfig
            .functionCallingConfig()
            .ifPresent(
                functionCallingConfig -> {
                  functionCallingConfig
                      .mode()
                      .ifPresent(
                          functionMode -> {
                            if (FunctionCallingConfigMode.Known.AUTO.equals(
                                functionMode.knownEnum())) {
                              requestBuilder.toolChoice(ToolChoice.AUTO);
                            } else if (FunctionCallingConfigMode.Known.ANY.equals(
                                functionMode.knownEnum())) {
                              // TODO check if it's the correct
                              // mapping
                              requestBuilder.toolChoice(ToolChoice.REQUIRED);
                              functionCallingConfig
                                  .allowedFunctionNames()
                                  .ifPresent(
                                      allowedFunctionNames -> {
                                        requestBuilder.toolSpecifications(
                                            toolSpecifications.stream()
                                                .filter(
                                                    toolSpecification ->
                                                        allowedFunctionNames.contains(
                                                            toolSpecification.name()))
                                                .toList());
                                      });
                            } else if (FunctionCallingConfigMode.Known.NONE.equals(
                                functionMode.knownEnum())) {
                              requestBuilder.toolSpecifications(List.of());
                            }
                          });
                });
        toolConfig
            .retrievalConfig()
            .ifPresent(
                retrievalConfig -> {
                  // TODO? It exposes Latitude / Longitude, what to do with this?
                });
      }
    }

    return requestBuilder.messages(toMessages(llmRequest)).build();
  }

  private List<ChatMessage> toMessages(LlmRequest llmRequest) {
    List<ChatMessage> messages =
        new ArrayList<>(
            llmRequest.getSystemInstructions().stream().map(SystemMessage::from).toList());
    llmRequest.contents().forEach(content -> messages.addAll(toChatMessage(content)));
    return messages;
  }

  private List<ChatMessage> toChatMessage(Content content) {
    String role = content.role().orElseThrow().toLowerCase();
    return switch (role) {
      case "user" -> toUserOrToolResultMessage(content);
      case "model", "assistant" -> List.of(toAiMessage(content));
      default -> throw new IllegalStateException("Unexpected role: " + role);
    };
  }

  private List<ChatMessage> toUserOrToolResultMessage(Content content) {
    List<ToolExecutionResultMessage> toolExecutionResultMessages = new ArrayList<>();
    List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();

    List<dev.langchain4j.data.message.Content> lc4jContents = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        lc4jContents.add(TextContent.from(part.text().get()));
      } else if (part.functionResponse().isPresent()) {
        FunctionResponse functionResponse = part.functionResponse().get();
        toolExecutionResultMessages.add(
            ToolExecutionResultMessage.from(
                functionResponse.id().orElseThrow(),
                functionResponse.name().orElseThrow(),
                toJson(functionResponse.response().orElseThrow())));
      } else if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        toolExecutionRequests.add(
            ToolExecutionRequest.builder()
                .id(functionCall.id().orElseThrow())
                .name(functionCall.name().orElseThrow())
                .arguments(toJson(functionCall.args().orElse(Map.of())))
                .build());
      } else if (part.inlineData().isPresent()) {
        Blob blob = part.inlineData().get();

        if (blob.mimeType().isEmpty() || blob.data().isEmpty()) {
          throw new IllegalArgumentException("Mime type and data required");
        }

        byte[] bytes = blob.data().get();
        String mimeType = blob.mimeType().get();

        Base64.Encoder encoder = Base64.getEncoder();

        dev.langchain4j.data.message.Content lc4jContent = null;

        if (mimeType.startsWith("audio/")) {
          lc4jContent =
              AudioContent.from(
                  Audio.builder()
                      .base64Data(encoder.encodeToString(bytes))
                      .mimeType(mimeType)
                      .build());
        } else if (mimeType.startsWith("video/")) {
          lc4jContent =
              VideoContent.from(
                  Video.builder()
                      .base64Data(encoder.encodeToString(bytes))
                      .mimeType(mimeType)
                      .build());
        } else if (mimeType.startsWith("image/")) {
          lc4jContent =
              ImageContent.from(
                  Image.builder()
                      .base64Data(encoder.encodeToString(bytes))
                      .mimeType(mimeType)
                      .build());
        } else if (mimeType.startsWith("application/pdf")) {
          lc4jContent =
              PdfFileContent.from(
                  PdfFile.builder()
                      .base64Data(encoder.encodeToString(bytes))
                      .mimeType(mimeType)
                      .build());
        } else if (mimeType.startsWith("text/")
            || "application/json".equals(mimeType)
            || mimeType.endsWith("+json")
            || mimeType.endsWith("+xml")) {
          // TODO are there missing text based mime types?
          // TODO should we assume UTF_8?
          lc4jContents.add(
              TextContent.from(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        }

        if (lc4jContent != null) {
          lc4jContents.add(lc4jContent);
        } else {
          throw new IllegalArgumentException("Unknown or unhandled mime type: " + mimeType);
        }
      } else {
        throw new IllegalStateException(
            "Text, media or functionCall is expected, but was: " + part);
      }
    }

    if (!toolExecutionResultMessages.isEmpty()) {
      return new ArrayList<ChatMessage>(toolExecutionResultMessages);
    } else if (!toolExecutionRequests.isEmpty()) {
      return toolExecutionRequests.stream()
          .map(AiMessage::aiMessage)
          .map(msg -> (ChatMessage) msg)
          .toList();
    } else {
      return List.of(UserMessage.from(lc4jContents));
    }
  }

  private AiMessage toAiMessage(Content content) {
    List<String> texts = new ArrayList<>();
    List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();

    content
        .parts()
        .orElse(List.of())
        .forEach(
            part -> {
              if (part.text().isPresent()) {
                texts.add(part.text().get());
              } else if (part.functionCall().isPresent()) {
                FunctionCall functionCall = part.functionCall().get();
                ToolExecutionRequest toolExecutionRequest =
                    ToolExecutionRequest.builder()
                        .id(functionCall.id().orElseThrow())
                        .name(functionCall.name().orElseThrow())
                        .arguments(toJson(functionCall.args().orElseThrow()))
                        .build();
                toolExecutionRequests.add(toolExecutionRequest);
              } else {
                throw new IllegalStateException(
                    "Either text or functionCall is expected, but was: " + part);
              }
            });

    return AiMessage.builder()
        .text(String.join("\n", texts))
        .toolExecutionRequests(toolExecutionRequests)
        .build();
  }

  private String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private List<ToolSpecification> toToolSpecifications(LlmRequest llmRequest) {
    List<ToolSpecification> toolSpecifications = new ArrayList<>();

    llmRequest
        .tools()
        .values()
        .forEach(
            baseTool -> {
              if (baseTool.declaration().isPresent()) {
                FunctionDeclaration functionDeclaration = baseTool.declaration().get();
                Schema schema = null;
                if (functionDeclaration.parametersJsonSchema().isPresent()) {
                  Object jsonSchemaObj = functionDeclaration.parametersJsonSchema().get();
                  try {
                    if (jsonSchemaObj instanceof Schema) {
                      schema = (Schema) jsonSchemaObj;
                    } else {
                      ObjectMapper adkMapper = JsonBaseModel.getMapper();
                      String jsonSchemaStr = adkMapper.writeValueAsString(jsonSchemaObj);
                      schema = adkMapper.readValue(jsonSchemaStr, Schema.class);
                    }
                  } catch (Exception e) {
                    throw new IllegalStateException(
                        "Failed to convert parametersJsonSchema to Schema: " + e.getMessage(), e);
                  }
                } else if (functionDeclaration.parameters().isPresent()) {
                  schema = functionDeclaration.parameters().get();
                }

                if (schema != null) {
                  ToolSpecification toolSpecification =
                      ToolSpecification.builder()
                          .name(baseTool.name())
                          .description(baseTool.description())
                          .parameters(toParameters(schema))
                          .build();
                  toolSpecifications.add(toolSpecification);
                } else {
                  throw new IllegalStateException("Tool lacking parameters: " + baseTool);
                }
              } else {
                throw new IllegalStateException("Tool lacking declaration: " + baseTool);
              }
            });

    return toolSpecifications;
  }

  private JsonObjectSchema toParameters(Schema schema) {
    if (schema.type().isPresent() && Type.Known.OBJECT.equals(schema.type().get().knownEnum())) {
      return JsonObjectSchema.builder()
          .addProperties(toProperties(schema))
          .required(schema.required().orElse(List.of()))
          .build();
    } else {
      throw new UnsupportedOperationException(
          "LangChain4jLlm does not support schema of type: " + schema.type());
    }
  }

  private Map<String, JsonSchemaElement> toProperties(Schema schema) {
    Map<String, Schema> properties = schema.properties().orElse(Map.of());
    Map<String, JsonSchemaElement> result = new HashMap<>();
    properties.forEach((k, v) -> result.put(k, toJsonSchemaElement(v)));
    return result;
  }

  private JsonSchemaElement toJsonSchemaElement(Schema schema) {
    if (schema != null && schema.type().isPresent()) {
      Type type = schema.type().get();
      return switch (type.knownEnum()) {
        case STRING ->
            JsonStringSchema.builder().description(schema.description().orElse(null)).build();
        case NUMBER ->
            JsonNumberSchema.builder().description(schema.description().orElse(null)).build();
        case INTEGER ->
            JsonIntegerSchema.builder().description(schema.description().orElse(null)).build();
        case BOOLEAN ->
            JsonBooleanSchema.builder().description(schema.description().orElse(null)).build();
        case ARRAY ->
            JsonArraySchema.builder()
                .description(schema.description().orElse(null))
                .items(toJsonSchemaElement(schema.items().orElseThrow()))
                .build();
        case OBJECT -> toParameters(schema);
        default ->
            throw new UnsupportedFeatureException(
                "LangChain4jLlm does not support schema of type: " + type);
      };
    } else {
      throw new IllegalArgumentException("Schema type cannot be null or absent");
    }
  }

  private LlmResponse toLlmResponse(ChatResponse chatResponse) {
    Content content =
        Content.builder().role("model").parts(toParts(chatResponse.aiMessage())).build();

    return LlmResponse.builder().content(content).build();
  }

  private List<Part> toParts(AiMessage aiMessage) {
    if (aiMessage.hasToolExecutionRequests()) {
      List<Part> parts = new ArrayList<>();
      aiMessage
          .toolExecutionRequests()
          .forEach(
              toolExecutionRequest -> {
                FunctionCall functionCall =
                    FunctionCall.builder()
                        .id(
                            toolExecutionRequest.id() != null
                                ? toolExecutionRequest.id()
                                : UUID.randomUUID().toString())
                        .name(toolExecutionRequest.name())
                        .args(toArgs(toolExecutionRequest))
                        .build();
                Part part = Part.builder().functionCall(functionCall).build();
                parts.add(part);
              });
      return parts;
    } else {
      Part part = Part.builder().text(aiMessage.text()).build();
      return List.of(part);
    }
  }

  private Map<String, Object> toArgs(ToolExecutionRequest toolExecutionRequest) {
    try {
      return objectMapper.readValue(toolExecutionRequest.arguments(), MAP_TYPE_REFERENCE);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        "Live connection is not supported for LangChain4j models.");
  }
}
