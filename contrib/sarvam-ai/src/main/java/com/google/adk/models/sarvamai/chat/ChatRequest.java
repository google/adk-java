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

package com.google.adk.models.sarvamai.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Request body for the Sarvam AI chat completions endpoint. Constructed from the ADK {@link
 * LlmRequest} and {@link SarvamAiConfig}.
 *
 * @author Sandeep Belgavi
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatRequest {

  @JsonProperty("model")
  private String model;

  @JsonProperty("messages")
  private List<ChatMessage> messages;

  @JsonProperty("stream")
  private Boolean stream;

  @JsonProperty("temperature")
  private Double temperature;

  @JsonProperty("top_p")
  private Double topP;

  @JsonProperty("max_tokens")
  private Integer maxTokens;

  @JsonProperty("reasoning_effort")
  private String reasoningEffort;

  @JsonProperty("wiki_grounding")
  private Boolean wikiGrounding;

  @JsonProperty("frequency_penalty")
  private Double frequencyPenalty;

  @JsonProperty("presence_penalty")
  private Double presencePenalty;

  @JsonProperty("n")
  private Integer n;

  @JsonProperty("seed")
  private Integer seed;

  @JsonProperty("stop")
  private Object stop;

  @JsonProperty("tools")
  private List<Map<String, Object>> tools;

  @JsonProperty("tool_choice")
  private String toolChoice;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String IDENTIFIER_REGEX = "[^a-zA-Z0-9_\\.-]";

  public ChatRequest() {}

  /**
   * Converts an ADK {@link LlmRequest} into a Sarvam-native {@link ChatRequest}, applying config
   * defaults and mapping ADK roles to OpenAI-compatible roles.
   */
  public static ChatRequest fromLlmRequest(
      String modelName, LlmRequest llmRequest, SarvamAiConfig config, boolean stream) {
    ChatRequest request = new ChatRequest();
    request.model = modelName;
    request.stream = stream ? true : null;
    request.messages = new ArrayList<>();

    for (String instruction : llmRequest.getSystemInstructions()) {
      request.messages.add(new ChatMessage("system", instruction));
    }

    for (Content content : llmRequest.contents()) {
      String role = content.role().orElse("user");
      if ("model".equals(role)) {
        role = "assistant";
      }
      List<Part> parts = content.parts().orElse(ImmutableList.of());
      if (parts.isEmpty()) {
        continue;
      }
      Part firstPart = parts.get(0);

      if (firstPart.functionResponse().isPresent()) {
        var fr = firstPart.functionResponse().get();
        ChatMessage toolMsg = new ChatMessage();
        toolMsg.setRole("tool");
        toolMsg.setToolCallId(fr.id().orElse("call_" + fr.name().orElse("unknown")));
        toolMsg.setContent(
            fr.response()
                .map(
                    r -> {
                      try {
                        return OBJECT_MAPPER.writeValueAsString(r);
                      } catch (Exception e) {
                        return "{}";
                      }
                    })
                .orElse("{}"));
        request.messages.add(toolMsg);
      } else if (firstPart.functionCall().isPresent()) {
        var fc = firstPart.functionCall().get();
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(null);
        ChatToolCall tc = new ChatToolCall();
        tc.setId(fc.id().orElse("call_" + fc.name().orElse("unknown")));
        tc.setType("function");
        ChatToolCall.ChatToolCallFunction tcf = new ChatToolCall.ChatToolCallFunction();
        tcf.setName(fc.name().orElse(""));
        tcf.setArguments(
            fc.args()
                .map(
                    args -> {
                      try {
                        return OBJECT_MAPPER.writeValueAsString(args);
                      } catch (Exception e) {
                        return "{}";
                      }
                    })
                .orElse("{}"));
        tc.setFunction(tcf);
        assistantMsg.setToolCalls(List.of(tc));
        request.messages.add(assistantMsg);
      } else {
        StringBuilder textBuilder = new StringBuilder();
        for (Part part : parts) {
          part.text().ifPresent(textBuilder::append);
        }
        if (textBuilder.length() > 0) {
          request.messages.add(
              new ChatMessage(role.equals("model") ? "assistant" : role, textBuilder.toString()));
        }
      }
    }

    if (!llmRequest.tools().isEmpty()) {
      request.tools = buildTools(llmRequest);
      request.toolChoice = "auto";
    }

    config.temperature().ifPresent(v -> request.temperature = v);
    config.topP().ifPresent(v -> request.topP = v);
    config.maxTokens().ifPresent(v -> request.maxTokens = v);
    config.reasoningEffort().ifPresent(v -> request.reasoningEffort = v);
    config.wikiGrounding().ifPresent(v -> request.wikiGrounding = v);
    config.frequencyPenalty().ifPresent(v -> request.frequencyPenalty = v);
    config.presencePenalty().ifPresent(v -> request.presencePenalty = v);

    return request;
  }

  private static List<Map<String, Object>> buildTools(LlmRequest llmRequest) {
    List<Map<String, Object>> toolsList = new ArrayList<>();
    llmRequest
        .tools()
        .forEach(
            (name, baseTool) -> {
              Optional<FunctionDeclaration> declOpt = baseTool.declaration();
              if (declOpt.isEmpty()) {
                return;
              }
              FunctionDeclaration decl = declOpt.get();
              Map<String, Object> funcMap = new java.util.HashMap<>();
              funcMap.put("name", cleanForIdentifier(decl.name().orElse("")));
              funcMap.put("description", cleanForIdentifier(decl.description().orElse("")));

              decl.parameters()
                  .ifPresent(
                      paramsSchema -> {
                        Map<String, Object> paramsMap = new java.util.HashMap<>();
                        paramsMap.put("type", "object");
                        paramsSchema
                            .properties()
                            .ifPresent(
                                props -> {
                                  Map<String, Object> propsMap = new java.util.HashMap<>();
                                  props.forEach(
                                      (key, schema) -> {
                                        Map<String, Object> schemaMap = schemaToMap(schema);
                                        normalizeTypeStrings(schemaMap);
                                        propsMap.put(key, schemaMap);
                                      });
                                  paramsMap.put("properties", propsMap);
                                });
                        paramsSchema.required().ifPresent(r -> paramsMap.put("required", r));
                        funcMap.put("parameters", paramsMap);
                      });

              Map<String, Object> toolWrapper = new java.util.HashMap<>();
              toolWrapper.put("type", "function");
              toolWrapper.put("function", funcMap);
              toolsList.add(toolWrapper);
            });
    return toolsList;
  }

  /** Manually convert Schema to Map to avoid Jackson Optional serialization issues. */
  private static Map<String, Object> schemaToMap(Schema schema) {
    Map<String, Object> map = new java.util.HashMap<>();
    schema.type().ifPresent(t -> map.put("type", schemaTypeToString(t)));
    schema.description().ifPresent(d -> map.put("description", d));
    schema
        .properties()
        .ifPresent(
            props -> {
              Map<String, Object> propsMap = new java.util.HashMap<>();
              props.forEach((k, v) -> propsMap.put(k, schemaToMap(v)));
              map.put("properties", propsMap);
            });
    schema.required().ifPresent(r -> map.put("required", r));
    schema.items().ifPresent(i -> map.put("items", schemaToMap(i)));
    return map;
  }

  private static String schemaTypeToString(Type type) {
    return switch (type.knownEnum()) {
      case STRING -> "string";
      case NUMBER -> "number";
      case INTEGER -> "integer";
      case BOOLEAN -> "boolean";
      case ARRAY -> "array";
      case OBJECT -> "object";
      default -> "string";
    };
  }

  private static String cleanForIdentifier(String input) {
    return input == null ? "" : input.replaceAll(IDENTIFIER_REGEX, "");
  }

  @SuppressWarnings("unchecked")
  private static void normalizeTypeStrings(Map<String, Object> valueDict) {
    if (valueDict == null) return;
    if (valueDict.containsKey("type") && valueDict.get("type") instanceof String) {
      valueDict.put("type", ((String) valueDict.get("type")).toLowerCase());
    }
    if (valueDict.containsKey("items") && valueDict.get("items") instanceof Map) {
      normalizeTypeStrings((Map<String, Object>) valueDict.get("items"));
    }
  }

  public String getModel() {
    return model;
  }

  public List<ChatMessage> getMessages() {
    return messages;
  }

  public Boolean getStream() {
    return stream;
  }

  public Double getTemperature() {
    return temperature;
  }

  public Double getTopP() {
    return topP;
  }

  public Integer getMaxTokens() {
    return maxTokens;
  }

  public String getReasoningEffort() {
    return reasoningEffort;
  }

  public Boolean getWikiGrounding() {
    return wikiGrounding;
  }

  public List<Map<String, Object>> getTools() {
    return tools;
  }

  public String getToolChoice() {
    return toolChoice;
  }
}
