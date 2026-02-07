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

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.MessageParam.Role;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the Claude Generative AI model by Anthropic.
 *
 * <p>This class provides methods for interacting with Claude models. Streaming and live connections
 * are not currently supported for Claude.
 */
public class Claude extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(Claude.class);
  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().registerModule(new Jdk8Module());
  private int maxTokens = 8192;
  private final AnthropicClient anthropicClient;

  /**
   * Constructs a new Claude instance.
   *
   * @param modelName The name of the Claude model to use (e.g., "claude-3-opus-20240229").
   * @param anthropicClient The Anthropic API client instance.
   */
  public Claude(String modelName, AnthropicClient anthropicClient) {
    super(modelName);
    this.anthropicClient = anthropicClient;
  }

  public Claude(String modelName, AnthropicClient anthropicClient, int maxTokens) {
    super(modelName);
    this.anthropicClient = anthropicClient;
    this.maxTokens = maxTokens;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    // TODO: Switch to streaming API.
    List<MessageParam> messages =
        llmRequest.contents().stream()
            .map(this::contentToAnthropicMessageParam)
            .collect(Collectors.toList());

    List<ToolUnion> tools = ImmutableList.of();
    if (llmRequest.config().isPresent()
        && llmRequest.config().get().tools().isPresent()
        && !llmRequest.config().get().tools().get().isEmpty()
        && llmRequest.config().get().tools().get().get(0).functionDeclarations().isPresent()) {
      tools =
          llmRequest.config().get().tools().get().get(0).functionDeclarations().get().stream()
              .map(this::functionDeclarationToAnthropicTool)
              .map(tool -> ToolUnion.ofTool(tool))
              .collect(Collectors.toList());
    }

    ToolChoice toolChoice =
        llmRequest.tools().isEmpty()
            ? null
            : ToolChoice.ofAuto(ToolChoiceAuto.builder().disableParallelToolUse(true).build());

    String systemText = "";
    Optional<GenerateContentConfig> configOpt = llmRequest.config();
    if (configOpt.isPresent()) {
      Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
      if (systemInstructionOpt.isPresent()) {
        String extractedSystemText =
            systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"));
        if (!extractedSystemText.isEmpty()) {
          systemText = extractedSystemText;
        }
      }
    }

    MessageCreateParams.Builder paramsBuilder =
        MessageCreateParams.builder()
            .model(llmRequest.model().orElse(model()))
            .system(systemText)
            .messages(messages)
            .maxTokens(this.maxTokens);

    if (toolChoice != null) {
      paramsBuilder.tools(tools);
      paramsBuilder.toolChoice(toolChoice);
    }

    var message = this.anthropicClient.messages().create(paramsBuilder.build());

    logger.debug("Claude response: {}", message);

    return Flowable.just(convertAnthropicResponseToLlmResponse(message));
  }

  private Role toClaudeRole(String role) {
    return role.equals("model") || role.equals("assistant") ? Role.ASSISTANT : Role.USER;
  }

  private MessageParam contentToAnthropicMessageParam(Content content) {
    return MessageParam.builder()
        .role(toClaudeRole(content.role().orElse("")))
        .contentOfBlockParams(
            content.parts().orElse(ImmutableList.of()).stream()
                .map(this::partToAnthropicMessageBlock)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()))
        .build();
  }

  private ContentBlockParam partToAnthropicMessageBlock(Part part) {
    if (part.text().isPresent()) {
      return ContentBlockParam.ofText(TextBlockParam.builder().text(part.text().get()).build());
    } else if (part.functionCall().isPresent()) {
      return ContentBlockParam.ofToolUse(
          ToolUseBlockParam.builder()
              .id(part.functionCall().get().id().orElse(""))
              .name(part.functionCall().get().name().orElseThrow())
              .type(com.anthropic.core.JsonValue.from("tool_use"))
              .input(
                  com.anthropic.core.JsonValue.from(
                      part.functionCall().get().args().orElse(ImmutableMap.of())))
              .build());
    } else if (part.functionResponse().isPresent()) {
      String content = "";
      if (part.functionResponse().get().response().isPresent()) {
        Map<String, Object> response = part.functionResponse().get().response().get();
        try {
          content = OBJECT_MAPPER.writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
          logger.error("Failed to serialize tool response", e);
          content = response.toString();
        }
      }
      return ContentBlockParam.ofToolResult(
          ToolResultBlockParam.builder()
              .toolUseId(part.functionResponse().get().id().orElse(""))
              .content(content)
              .isError(false)
              .build());
    }
    throw new UnsupportedOperationException("Not supported yet.");
  }

  private void updateTypeString(Map<String, Object> valueDict) {
    if (valueDict == null) {
      return;
    }
    if (valueDict.containsKey("type")) {
      valueDict.put("type", ((String) valueDict.get("type")).toLowerCase());
    }

    if (valueDict.containsKey("items")) {
      updateTypeString((Map<String, Object>) valueDict.get("items"));

      if (valueDict.get("items") instanceof Map
          && ((Map) valueDict.get("items")).containsKey("properties")) {
        Map<String, Object> properties =
            (Map<String, Object>) ((Map) valueDict.get("items")).get("properties");
        if (properties != null) {
          for (Object value : properties.values()) {
            if (value instanceof Map) {
              updateTypeString((Map<String, Object>) value);
            }
          }
        }
      }
    }
  }

  private Tool functionDeclarationToAnthropicTool(FunctionDeclaration functionDeclaration) {
    Map<String, Map<String, Object>> properties = new HashMap<>();
    if (functionDeclaration.parameters().isPresent()
        && functionDeclaration.parameters().get().properties().isPresent()) {
      functionDeclaration
          .parameters()
          .get()
          .properties()
          .get()
          .forEach(
              (key, schema) -> {
                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.registerModule(new Jdk8Module());
                Map<String, Object> schemaMap =
                    objectMapper.convertValue(schema, new TypeReference<Map<String, Object>>() {});
                updateTypeString(schemaMap);
                properties.put(key, schemaMap);
              });
    }

    return Tool.builder()
        .name(functionDeclaration.name().orElseThrow())
        .description(functionDeclaration.description().orElse(""))
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(com.anthropic.core.JsonValue.from(properties))
                .build())
        .build();
  }

  private LlmResponse convertAnthropicResponseToLlmResponse(Message message) {
    LlmResponse.Builder responseBuilder = LlmResponse.builder();
    List<Part> parts = new ArrayList<>();

    if (message.content() != null) {
      for (ContentBlock block : message.content()) {
        Part part = anthropicContentBlockToPart(block);
        if (part != null) {
          parts.add(part);
        }
      }
      responseBuilder.content(
          Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());
    }
    return responseBuilder.build();
  }

  private Part anthropicContentBlockToPart(ContentBlock block) {
    if (block.isText()) {
      return Part.builder().text(block.asText().text()).build();
    } else if (block.isToolUse()) {
      return Part.builder()
          .functionCall(
              FunctionCall.builder()
                  .id(block.asToolUse().id())
                  .name(block.asToolUse().name())
                  .args(
                      block
                          .asToolUse()
                          ._input()
                          .convert(new TypeReference<Map<String, Object>>() {}))
                  .build())
          .build();
    }
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("Live connection is not supported for Claude models.");
  }
}
