package com.google.adk.base.serializer;

import static com.google.adk.base.serializer.SerializerUtils.ARGUMENTS;
import static com.google.adk.base.serializer.SerializerUtils.CHOICES;
import static com.google.adk.base.serializer.SerializerUtils.CONTENT;
import static com.google.adk.base.serializer.SerializerUtils.DELTA;
import static com.google.adk.base.serializer.SerializerUtils.ERROR;
import static com.google.adk.base.serializer.SerializerUtils.FUNCTION;
import static com.google.adk.base.serializer.SerializerUtils.ID;
import static com.google.adk.base.serializer.SerializerUtils.INDEX;
import static com.google.adk.base.serializer.SerializerUtils.MAPPER;
import static com.google.adk.base.serializer.SerializerUtils.MESSAGE;
import static com.google.adk.base.serializer.SerializerUtils.NAME;
import static com.google.adk.base.serializer.SerializerUtils.TOOL_CALLS;
import static com.google.adk.base.serializer.SerializerUtils.content;
import static com.google.adk.base.serializer.SerializerUtils.finalTextResponse;
import static com.google.adk.base.serializer.SerializerUtils.parseJsonArgs;
import static com.google.genai.types.Part.fromFunctionCall;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.FlowableEmitter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Jackson-backed {@link OpenAiMessageSerializer} for the OpenAI chat-completions format. */
public final class DefaultOpenAiMessageSerializer implements OpenAiMessageSerializer {

  private static final String SSE_DATA_PREFIX = "data: ";
  private static final String SSE_DONE = "data: [DONE]";

  @Override
  public String serializeRequest(LlmRequest request, String modelName, boolean stream)
      throws Exception {
    RequestFunction function = new RequestFunction(modelName, stream);
    return function.apply(request);
  }

  @Override
  public LlmResponse deserializeResponse(String responseBody) throws Exception {
    return ResponseFunction.INSTANCE.apply(responseBody);
  }

  @Override
  public void processStreamLines(Stream<String> lines, FlowableEmitter<LlmResponse> emitter)
      throws Exception {
    StringBuilder sb = new StringBuilder();
    List<ObjectNode> nodes = new ArrayList<>();
    Iterator<String> it = lines.iterator();
    while (it.hasNext()) {
      String line = it.next();
      if (SSE_DONE.equals(line)) {
        break;
      }
      if (!line.startsWith(SSE_DATA_PREFIX)) {
        continue;
      }

      String json = line.substring(SSE_DATA_PREFIX.length()).trim();
      if (isBlank(json)) {
        continue;
      }

      JsonNode chunk;
      try {
        chunk = MAPPER.readTree(json);
      } catch (Exception e) {
        continue;
      }

      if (chunk.has(ERROR)) {
        String msg = chunk.path(ERROR).path(MESSAGE).asText(json);
        throw new IllegalArgumentException("Stream error: %s".formatted(msg));
      }

      JsonNode delta = chunk.path(CHOICES).path(0).path(DELTA);
      // Text delta
      String textDelta = delta.path(CONTENT).asText(null);
      if (isNotBlank(textDelta)) {
        sb.append(textDelta);
        emitter.onNext(partialTextResponse(textDelta));
      }

      // Tool call delta - arguments arrive fragmented across chunks
      JsonNode toolCalls = delta.path(TOOL_CALLS);
      if (toolCalls.isArray()) {
        for (JsonNode tc : toolCalls) {
          int index = tc.path(INDEX).asInt(0);
          while (nodes.size() <= index) {
            nodes.add(MAPPER.createObjectNode());
          }
          ObjectNode acc = nodes.get(index);
          if (tc.has(ID)) {
            acc.put(ID, tc.path(ID).asText(""));
          }

          if (tc.path(FUNCTION).has(NAME)) {
            acc.put(NAME, tc.path(FUNCTION).path(NAME).asText(""));
          }

          if (tc.path(FUNCTION).has(ARGUMENTS)) {
            String arg = acc.path(ARGUMENTS).asText("");
            String farg = tc.path(FUNCTION).path(ARGUMENTS).asText("");
            acc.put(ARGUMENTS, arg + farg);
          }
        }
      }
    }
    emitter.onNext(nodes.isEmpty() ? finalTextResponse(sb.toString()) : toolCallResponse(nodes));
  }

  private LlmResponse toolCallResponse(List<ObjectNode> nodes) throws Exception {
    List<Part> parts = new ArrayList<>();
    for (ObjectNode node : nodes) {
      Map<String, Object> args = parseJsonArgs(node.path(ARGUMENTS).asText("{}"));
      parts.add(fromFunctionCall(node.path(NAME).asText(""), args));
    }
    return LlmResponse.builder() //
        .content(content(parts)) //
        .turnComplete(false) //
        .build();
  }

  private LlmResponse partialTextResponse(String token) {
    return LlmResponse.builder() //
        .content(content(List.of(Part.fromText(token)))) //
        .partial(true) //
        .turnComplete(false) //
        .build();
  }
}
