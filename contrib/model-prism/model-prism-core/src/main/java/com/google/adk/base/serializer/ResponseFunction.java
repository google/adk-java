package com.google.adk.base.serializer;

import static com.google.adk.base.serializer.SerializerUtils.ARGUMENTS;
import static com.google.adk.base.serializer.SerializerUtils.CHOICES;
import static com.google.adk.base.serializer.SerializerUtils.CONTENT;
import static com.google.adk.base.serializer.SerializerUtils.FUNCTION;
import static com.google.adk.base.serializer.SerializerUtils.MAPPER;
import static com.google.adk.base.serializer.SerializerUtils.MESSAGE;
import static com.google.adk.base.serializer.SerializerUtils.NAME;
import static com.google.adk.base.serializer.SerializerUtils.TOOL_CALLS;
import static com.google.adk.base.serializer.SerializerUtils.content;
import static com.google.adk.base.serializer.SerializerUtils.finalTextResponse;
import static com.google.adk.base.serializer.SerializerUtils.parseJsonArgs;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public enum ResponseFunction implements Function<String, LlmResponse> {
  INSTANCE;

  @Override
  public LlmResponse apply(String input) {
    try {
      JsonNode root = MAPPER.readTree(input);
      JsonNode message = root.path(CHOICES).path(0).path(MESSAGE);
      JsonNode toolCalls = message.path(TOOL_CALLS);
      if (toolCalls.isArray() && !toolCalls.isEmpty()) {
        return toolCallsResponse(toolCalls);
      }
      return finalTextResponse(message.path(CONTENT).asText(""));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private LlmResponse toolCallsResponse(JsonNode toolCalls) throws Exception {
    List<Part> parts = new ArrayList<>();
    for (JsonNode tc : toolCalls) {
      String txt = tc.path(FUNCTION).path(ARGUMENTS).asText("{}");
      String name = tc.path(FUNCTION).path(NAME).asText("");
      Map<String, Object> args = parseJsonArgs(txt);
      parts.add(Part.fromFunctionCall(name, args));
    }
    return LlmResponse.builder() //
        .content(content(parts)) //
        .turnComplete(false)
        .build(); //
  }
}
