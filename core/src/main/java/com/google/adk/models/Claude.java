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
import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

  // JSON Schema keywords traversed by updateTypeString, grouped by the shape of their value.
  // Keywords whose value is a map of named sub-schemas (e.g. "properties": {"a": {...}}).
  private static final ImmutableList<String> NESTED_SCHEMA_MAP_KEYWORDS =
      ImmutableList.of("$defs", "defs", "dependentSchemas", "patternProperties", "properties");
  // Keywords whose value is a single sub-schema (e.g. "items": {...}).
  private static final ImmutableList<String> NESTED_SCHEMA_KEYWORDS =
      ImmutableList.of(
          "additionalProperties",
          "additional_properties",
          "contains",
          "else",
          "if",
          "items",
          "not",
          "propertyNames",
          "then",
          "unevaluatedProperties");
  // Keywords whose value is a list of sub-schemas (e.g. "anyOf": [{...}, {...}]).
  private static final ImmutableList<String> NESTED_SCHEMA_LIST_KEYWORDS =
      ImmutableList.of("allOf", "all_of", "anyOf", "any_of", "oneOf", "one_of", "prefixItems");

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
        Map<String, Object> responseData = part.functionResponse().get().response().get();

        Object resultObj = responseData.get("result");
        if (resultObj != null) {
          content = resultObj.toString();
        } else {
          // Fallback to json serialization of the function response.
          content = serializeToJson(responseData);
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

  private String serializeToJson(Object obj) {
    try {
      return JsonBaseModel.getMapper().writeValueAsString(obj);
    } catch (Exception e) {
      logger.warn("Failed to serialize object to JSON", e);
      return String.valueOf(obj);
    }
  }

  /**
   * Recursively lowercases JSON Schema {@code type} keywords for Anthropic compatibility.
   *
   * <p>{@code type} is only lowercased when it is a plain string. JSON Schema also permits a union
   * type array (e.g. {@code ["string", "null"]}), which MCP tools can emit; those are traversed
   * rather than cast to {@code String}. All nested sub-schemas (properties, {@code $defs}, {@code
   * anyOf}, {@code items}, ...) are visited so deeply-nested types are normalized too.
   */
  @SuppressWarnings("unchecked")
  private void updateTypeString(Object value) {
    if (value instanceof List) {
      for (Object item : (List<Object>) value) {
        updateTypeString(item);
      }
      return;
    }
    if (!(value instanceof Map)) {
      return;
    }
    Map<String, Object> valueDict = (Map<String, Object>) value;

    Object schemaType = valueDict.get("type");
    if (schemaType instanceof String) {
      valueDict.put("type", ((String) schemaType).toLowerCase(Locale.ROOT));
    }

    for (String dictKey : NESTED_SCHEMA_MAP_KEYWORDS) {
      Object child = valueDict.get(dictKey);
      if (child instanceof Map) {
        for (Object childValue : ((Map<String, Object>) child).values()) {
          updateTypeString(childValue);
        }
      }
    }

    for (String singleKey : NESTED_SCHEMA_KEYWORDS) {
      updateTypeString(valueDict.get(singleKey));
    }

    for (String listKey : NESTED_SCHEMA_LIST_KEYWORDS) {
      updateTypeString(valueDict.get(listKey));
    }
  }

  private Tool functionDeclarationToAnthropicTool(FunctionDeclaration functionDeclaration) {
    Map<String, Object> inputSchema;
    if (functionDeclaration.parametersJsonSchema().isPresent()) {
      // MCP tools populate parametersJsonSchema (a raw JSON Schema object) instead of the
      // structured parameters() field. Pass the whole schema through -- as a mutable copy -- so
      // keys such as $ref/$defs/additionalProperties are preserved rather than dropped, then
      // lowercase any type strings for Anthropic compatibility.
      inputSchema =
          JsonBaseModel.getMapper()
              .convertValue(
                  functionDeclaration.parametersJsonSchema().get(),
                  new TypeReference<Map<String, Object>>() {});
    } else {
      Map<String, Object> properties = new HashMap<>();
      List<String> required = new ArrayList<>();
      if (functionDeclaration.parameters().isPresent()
          && functionDeclaration.parameters().get().properties().isPresent()) {
        functionDeclaration
            .parameters()
            .get()
            .properties()
            .get()
            .forEach(
                (key, schema) ->
                    properties.put(
                        key,
                        JsonBaseModel.getMapper()
                            .convertValue(schema, new TypeReference<Map<String, Object>>() {})));
        functionDeclaration.parameters().get().required().ifPresent(required::addAll);
      }
      inputSchema = new HashMap<>();
      inputSchema.put("type", "object");
      inputSchema.put("properties", properties);
      if (!required.isEmpty()) {
        inputSchema.put("required", required);
      }
    }
    updateTypeString(inputSchema);

    return Tool.builder()
        .name(functionDeclaration.name().orElseThrow())
        .description(functionDeclaration.description().orElse(""))
        .inputSchema(toAnthropicInputSchema(inputSchema))
        .build();
  }

  /**
   * Builds an Anthropic {@link Tool.InputSchema} from a JSON Schema map, preserving every top-level
   * keyword (e.g. {@code $defs}, {@code additionalProperties}) as an additional property so schemas
   * that rely on {@code $ref}/{@code $defs} are not sent to Claude with dangling references.
   */
  private Tool.InputSchema toAnthropicInputSchema(Map<String, Object> schema) {
    Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
    schema.forEach(
        (key, value) -> {
          switch (key) {
            case "type":
              // The Anthropic input schema type is always "object"; skip to avoid a duplicate key.
              break;
            case "properties":
              builder.properties(
                  com.anthropic.core.JsonValue.from(value == null ? new HashMap<>() : value));
              break;
            case "required":
              if (value instanceof List) {
                List<String> required = new ArrayList<>();
                for (Object item : (List<?>) value) {
                  if (item != null) {
                    required.add(item.toString());
                  }
                }
                builder.required(required);
              } else {
                builder.putAdditionalProperty(key, com.anthropic.core.JsonValue.from(value));
              }
              break;
            default:
              builder.putAdditionalProperty(key, com.anthropic.core.JsonValue.from(value));
          }
        });
    return builder.build();
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
    if (message.usage() != null) {
      responseBuilder.usageMetadata(
          GenerateContentResponseUsageMetadata.builder()
              .promptTokenCount((int) message.usage().inputTokens())
              .candidatesTokenCount((int) message.usage().outputTokens())
              .totalTokenCount(
                  (int) (message.usage().inputTokens() + message.usage().outputTokens()))
              .build());
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
