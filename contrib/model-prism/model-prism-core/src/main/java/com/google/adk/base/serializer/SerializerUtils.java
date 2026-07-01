package com.google.adk.base.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;

public final class SerializerUtils {

  protected static final ObjectMapper MAPPER = new ObjectMapper();

  protected static final String ID = "id";
  protected static final String ROLE = "role";
  protected static final String NAME = "name";
  protected static final String TYPE = "type";
  protected static final String USER = "user";
  protected static final String TOOL = "tool";
  protected static final String TOOLS = "tools";
  protected static final String MODEL = "model";
  protected static final String ERROR = "error";
  protected static final String DELTA = "delta";
  protected static final String INDEX = "index";
  protected static final String OBJECT = "object";
  protected static final String CALL_0 = "call_0";
  protected static final String STREAM = "stream";
  protected static final String SYSTEM = "system";
  protected static final String CHOICES = "choices";
  protected static final String CONTENT = "content";
  protected static final String MESSAGE = "message";
  protected static final String MESSAGES = "messages";
  protected static final String FUNCTION = "function";
  protected static final String ARGUMENTS = "arguments";
  protected static final String ASSISTANT = "assistant";
  protected static final String TOOL_CALLS = "tool_calls";
  protected static final String PARAMETERS = "parameters";
  protected static final String DESCRIPTION = "description";
  protected static final String TOOL_CALL_ID = "tool_call_id";

  protected static LlmResponse finalTextResponse(String fullText) {
    List<Part> parts = List.of(Part.fromText(fullText));
    return LlmResponse.builder() //
        .content(content(parts)) //
        .partial(false) //
        .turnComplete(true) //
        .build();
  }

  protected static Content content(List<Part> parts) {
    return Content.builder().role(MODEL).parts(parts).build();
  }

  @SuppressWarnings("unchecked")
  protected static Map<String, Object> parseJsonArgs(String json) throws Exception {
    return MAPPER.readValue(json, Map.class);
  }
}
