package com.google.adk.base.serializer;

import static com.google.adk.base.serializer.SerializerUtils.ARGUMENTS;
import static com.google.adk.base.serializer.SerializerUtils.ASSISTANT;
import static com.google.adk.base.serializer.SerializerUtils.CALL_0;
import static com.google.adk.base.serializer.SerializerUtils.CONTENT;
import static com.google.adk.base.serializer.SerializerUtils.DESCRIPTION;
import static com.google.adk.base.serializer.SerializerUtils.FUNCTION;
import static com.google.adk.base.serializer.SerializerUtils.ID;
import static com.google.adk.base.serializer.SerializerUtils.MAPPER;
import static com.google.adk.base.serializer.SerializerUtils.MESSAGES;
import static com.google.adk.base.serializer.SerializerUtils.MODEL;
import static com.google.adk.base.serializer.SerializerUtils.NAME;
import static com.google.adk.base.serializer.SerializerUtils.OBJECT;
import static com.google.adk.base.serializer.SerializerUtils.PARAMETERS;
import static com.google.adk.base.serializer.SerializerUtils.ROLE;
import static com.google.adk.base.serializer.SerializerUtils.STREAM;
import static com.google.adk.base.serializer.SerializerUtils.SYSTEM;
import static com.google.adk.base.serializer.SerializerUtils.TOOL;
import static com.google.adk.base.serializer.SerializerUtils.TOOLS;
import static com.google.adk.base.serializer.SerializerUtils.TOOL_CALLS;
import static com.google.adk.base.serializer.SerializerUtils.TOOL_CALL_ID;
import static com.google.adk.base.serializer.SerializerUtils.TYPE;
import static com.google.adk.base.serializer.SerializerUtils.USER;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class RequestFunction implements Function<LlmRequest, String> {

  public RequestFunction(String modelName, boolean stream) {
    this.stream = stream;
    this.modelName = checkNotNull(modelName, "modelName");
  }

  private final boolean stream;
  private final String modelName;

  @Override
  public String apply(LlmRequest request) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put(MODEL, modelName);
      body.put(STREAM, stream);

      ArrayNode messages = body.putArray(MESSAGES);
      request
          .getFirstSystemInstruction()
          .ifPresent(inst -> messages.addObject().put(ROLE, SYSTEM).put(CONTENT, inst));
      for (Content content : request.contents()) {
        String role = content.role().orElse(USER);
        if (MODEL.equals(role)) {
          role = ASSISTANT;
        }
        appendContentParts(messages, role, content);
      }

      if (!request.tools().isEmpty()) {
        ArrayNode tools = body.putArray(TOOLS);
        for (BaseTool tool : request.tools().values()) {
          tool.declaration().ifPresent(decl -> appendToolDeclaration(tools, decl));
        }
      }
      return MAPPER.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  private void appendContentParts(ArrayNode messages, String role, Content content)
      throws Exception {
    List<Part> parts = content.parts().orElse(List.of());
    List<Part> callParts = parts.stream().filter(p -> p.functionCall().isPresent()).toList();
    if (!callParts.isEmpty()) {
      appendCallParts(messages, callParts);
      return;
    }
    List<Part> respParts = parts.stream().filter(p -> p.functionResponse().isPresent()).toList();
    if (!respParts.isEmpty()) {
      appendResponseParts(messages, respParts);
      return;
    }
    String text = content.text();
    if (isNotBlank(text)) {
      messages.addObject().put(ROLE, role).put(CONTENT, text);
    }
  }

  private void appendResponseParts(ArrayNode messages, List<Part> responseParts) throws Exception {
    for (Part part : responseParts) {
      var fr = part.functionResponse().get();
      messages
          .addObject() //
          .put(ROLE, TOOL) //
          .put(TOOL_CALL_ID, fr.id().orElse(fr.name().orElse(CALL_0))) //
          .put(CONTENT, MAPPER.writeValueAsString(fr.response().orElse(Map.of())));
    }
  }

  private void appendCallParts(ArrayNode messages, List<Part> callParts) throws Exception {
    ObjectNode msg = messages.addObject();
    msg.put(ROLE, ASSISTANT);
    ArrayNode toolCalls = msg.putArray(TOOL_CALLS);
    for (Part part : callParts) {
      FunctionCall fc = part.functionCall().get();
      ObjectNode tc = toolCalls.addObject();
      tc.put(ID, fc.id().orElse(fc.name().orElse(CALL_0)));
      tc.put(TYPE, FUNCTION);
      tc.putObject(FUNCTION) //
          .put(NAME, fc.name().orElse("")) //
          .put(ARGUMENTS, MAPPER.writeValueAsString(fc.args().orElse(Map.of())));
    }
  }

  private void appendToolDeclaration(ArrayNode toolsArray, FunctionDeclaration decl) {
    try {
      ObjectNode node = toolsArray.addObject().put(TYPE, FUNCTION).putObject(FUNCTION);
      node.put(NAME, decl.name().orElse(""));
      node.put(DESCRIPTION, decl.description().orElse(""));
      if (decl.parameters().isPresent()) {
        node.set(
            PARAMETERS, normalizeSchemaTypes(MAPPER.readTree(decl.parameters().get().toJson())));
      } else {
        node.putObject(PARAMETERS).put(TYPE, OBJECT);
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to serialize tool declaration: " + decl.name(), e);
    }
  }

  private JsonNode normalizeSchemaTypes(JsonNode node) {
    if (node.isObject()) {
      ObjectNode obj = (ObjectNode) node;
      if (obj.has(TYPE)) {
        obj.put(TYPE, obj.get(TYPE).asText().toLowerCase());
      }
      obj.fields().forEachRemaining(entry -> normalizeSchemaTypes(entry.getValue()));
    } else if (node.isArray()) {
      node.forEach(child -> normalizeSchemaTypes(child));
    }
    return node;
  }
}
