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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.Properties;
import com.anthropic.core.http.Headers;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.MessageParam.Role;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.common.base.Utf8;
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
import org.jspecify.annotations.Nullable;
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
   * Constructs a new Claude instance. Requests sent through {@code anthropicClient} carry ADK's
   * usage-tracking headers, added to the ones the client already sends.
   *
   * @param modelName The name of the Claude model to use (e.g., "claude-3-opus-20240229").
   * @param anthropicClient The Anthropic API client instance.
   */
  public Claude(String modelName, AnthropicClient anthropicClient) {
    super(modelName);
    this.anthropicClient = withTrackingHeaders(anthropicClient);
  }

  public Claude(String modelName, AnthropicClient anthropicClient, int maxTokens) {
    super(modelName);
    this.anthropicClient = withTrackingHeaders(anthropicClient);
    this.maxTokens = maxTokens;
  }

  /**
   * Returns a copy of {@code client} that shares its connections and sends ADK's tracking headers,
   * extending any the client already sets. A null client, or a test double that returns no copy, is
   * returned unchanged.
   */
  private static @Nullable AnthropicClient withTrackingHeaders(@Nullable AnthropicClient client) {
    if (client == null) {
      return null;
    }
    AnthropicClient tracked =
        client.withOptions(
            options -> {
              Headers configured = options.build().headers();
              Map<String, String> current = new HashMap<>();
              // Start from the SDK's default User-Agent, which it adds only when none is set.
              current.put(
                  "user-agent",
                  client.getClass().getSimpleName() + "/Java " + Properties.getPackageVersion());
              for (String name : TrackingHeaders.HEADERS.keySet()) {
                List<String> values = configured.values(name);
                if (!values.isEmpty()) {
                  current.put(name, String.join(" ", values));
                }
              }
              TrackingHeaders.mergeInto(current).forEach(options::replaceHeaders);
            });
    return tracked != null ? tracked : client;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    // TODO: Switch to streaming API.
    List<MessageParam> messages =
        llmRequest.contents().stream()
            .map(this::contentToAnthropicMessageParam)
            // Anthropic rejects empty messages, e.g. a turn whose parts were all dropped.
            .filter(message -> !message.content().asBlockParams().isEmpty())
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
    Role role = toClaudeRole(content.role().orElse(""));
    List<Part> parts = content.parts().orElse(ImmutableList.of());
    List<ContentBlockParam> blocks =
        parts.stream()
            .map(this::partToAnthropicMessageBlock)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    // Keep a user turn of only empty text as ".", or a last assistant turn would be prefill.
    if (blocks.isEmpty()
        && role.equals(Role.USER)
        && parts.stream().anyMatch(Claude::isEmptyTextPart)) {
      blocks =
          ImmutableList.of(ContentBlockParam.ofText(TextBlockParam.builder().text(".").build()));
    }
    return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
  }

  /** Whether a part has any of the fields ADK Python counts as content. */
  private static boolean hasContentFields(Part part) {
    return part.functionCall().isPresent()
        || part.functionResponse().isPresent()
        || part.inlineData().isPresent()
        || part.fileData().isPresent()
        || part.executableCode().isPresent()
        || part.codeExecutionResult().isPresent()
        || part.toolCall().isPresent()
        || part.toolResponse().isPresent()
        || part.audioTranscription().isPresent();
  }

  /** Whether a non-thought part carries a signature and nothing to send, as in ADK Python. */
  private static boolean isContentFreeSignature(Part part) {
    return part.thoughtSignature().map(signature -> signature.length > 0).orElse(false)
        && !part.thought().orElse(false)
        && part.text().map(String::isEmpty).orElse(true)
        && !hasContentFields(part);
  }

  /** Whether a part carries only empty text, which Anthropic rejects; mirrors ADK Python. */
  private static boolean isEmptyTextPart(Part part) {
    return part.text().map(String::isEmpty).orElse(false)
        && part.thoughtSignature().map(signature -> signature.length == 0).orElse(true)
        && !hasContentFields(part);
  }

  private @Nullable ContentBlockParam partToAnthropicMessageBlock(Part part) {
    if (isContentFreeSignature(part)) {
      logger.warn("Dropping a thought signature from another model.");
      return null;
    }
    if (isEmptyTextPart(part)) {
      return null;
    }
    if (part.thought().orElse(false)
        && part.functionCall().isEmpty()
        && part.functionResponse().isEmpty()) {
      // Signatures this class stores are UTF-8; a binary one (e.g. Gemini's) is not Claude's.
      Optional<String> signature =
          part.thoughtSignature()
              .filter(bytes -> bytes.length > 0 && Utf8.isWellFormed(bytes))
              .map(bytes -> new String(bytes, UTF_8));
      if (signature.isPresent()) {
        // Unlike ADK Python, empty text (display "omitted") stays thinking, not redacted_thinking.
        return part.text().isPresent()
            ? ContentBlockParam.ofThinking(
                ThinkingBlockParam.builder()
                    .thinking(part.text().get())
                    .signature(signature.get())
                    .build())
            : ContentBlockParam.ofRedactedThinking(
                RedactedThinkingBlockParam.builder().data(signature.get()).build());
      }
      // Other thoughts go out as text (ADK Python sends thinking), or are dropped without text.
      if (part.text().map(String::isEmpty).orElse(true)) {
        return null;
      }
    }
    // Unlike ADK Python, a call or response is always sent, even on a thought or beside text.
    if (part.functionCall().isPresent()) {
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
    } else if (part.text().isPresent()) {
      return ContentBlockParam.ofText(TextBlockParam.builder().text(part.text().get()).build());
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
    message
        .stopReason()
        .map(reason -> toFinishReason(reason.asString()))
        .ifPresent(responseBuilder::finishReason);
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

  /** Maps an Anthropic stop reason to a finish reason, as ADK Python does. */
  private static FinishReason toFinishReason(String stopReason) {
    return switch (stopReason) {
      case "end_turn", "stop_sequence", "tool_use", "pause_turn" ->
          new FinishReason(FinishReason.Known.STOP.toString());
      case "max_tokens", "model_context_window_exceeded" ->
          new FinishReason(FinishReason.Known.MAX_TOKENS.toString());
      case "refusal" -> new FinishReason(FinishReason.Known.SAFETY.toString());
      default -> new FinishReason(FinishReason.Known.FINISH_REASON_UNSPECIFIED.toString());
    };
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
    } else if (block.isThinking()) {
      ThinkingBlock thinking = block.asThinking();
      Part.Builder part = Part.builder().text(thinking.thinking()).thought(true);
      if (!thinking.signature().isEmpty()) {
        part.thoughtSignature(thinking.signature().getBytes(UTF_8));
      }
      return part.build();
    } else if (block.isRedactedThinking()) {
      // Keep the encrypted data in thoughtSignature so the block can be sent back unchanged.
      return Part.builder()
          .thought(true)
          .thoughtSignature(block.asRedactedThinking().data().getBytes(UTF_8))
          .build();
    }
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("Live connection is not supported for Claude models.");
  }
}
