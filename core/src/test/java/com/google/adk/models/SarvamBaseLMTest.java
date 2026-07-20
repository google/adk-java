package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
/**
 * @author Sandeep Belgavi
 */
public final class SarvamBaseLMTest {

  // ========== openAiMessageToParts tests ==========

  @Test
  public void openAiMessageToParts_textContent_returnsTextPart() {
    JSONObject message = new JSONObject();
    message.put("role", "assistant");
    message.put("content", "Hello world");

    List<Part> parts = SarvamBaseLM.openAiMessageToParts(message);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("Hello world");
    assertThat(parts.get(0).functionCall()).isEmpty();
  }

  @Test
  public void openAiMessageToParts_nullContent_returnsEmptyTextPart() {
    JSONObject message = new JSONObject();
    message.put("role", "assistant");
    message.put("content", JSONObject.NULL);

    List<Part> parts = SarvamBaseLM.openAiMessageToParts(message);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("");
  }

  @Test
  public void openAiMessageToParts_missingContent_returnsEmptyTextPart() {
    JSONObject message = new JSONObject();
    message.put("role", "assistant");

    List<Part> parts = SarvamBaseLM.openAiMessageToParts(message);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("");
  }

  @Test
  public void openAiMessageToParts_toolCall_returnsFunctionCallPart() {
    JSONObject function = new JSONObject();
    function.put("name", "getBusSearch");
    function.put("arguments", "{\"source\":\"Bangalore\",\"dest\":\"Chennai\"}");

    JSONObject toolCall = new JSONObject();
    toolCall.put("id", "call_abc123");
    toolCall.put("type", "function");
    toolCall.put("function", function);

    JSONArray toolCalls = new JSONArray();
    toolCalls.put(toolCall);

    JSONObject message = new JSONObject();
    message.put("role", "assistant");
    message.put("content", JSONObject.NULL);
    message.put("tool_calls", toolCalls);

    List<Part> parts = SarvamBaseLM.openAiMessageToParts(message);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).functionCall()).isPresent();

    FunctionCall fc = parts.get(0).functionCall().get();
    assertThat(fc.name()).hasValue("getBusSearch");
    assertThat(fc.args()).isPresent();
    assertThat(fc.args().get()).containsEntry("source", "Bangalore");
    assertThat(fc.args().get()).containsEntry("dest", "Chennai");
  }

  @Test
  public void openAiMessageToParts_toolCallWithEmptyArgs_returnsFunctionCallWithEmptyMap() {
    JSONObject function = new JSONObject();
    function.put("name", "getOffers");
    function.put("arguments", "{}");

    JSONObject toolCall = new JSONObject();
    toolCall.put("id", "call_xyz");
    toolCall.put("type", "function");
    toolCall.put("function", function);

    JSONArray toolCalls = new JSONArray();
    toolCalls.put(toolCall);

    JSONObject message = new JSONObject();
    message.put("role", "assistant");
    message.put("tool_calls", toolCalls);

    List<Part> parts = SarvamBaseLM.openAiMessageToParts(message);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).functionCall()).isPresent();
    assertThat(parts.get(0).functionCall().get().name()).hasValue("getOffers");
    assertThat(parts.get(0).functionCall().get().args().get()).isEmpty();
  }

  @Test
  public void openAiMessageToParts_toolCallTakesPriorityOverContent() {
    JSONObject function = new JSONObject();
    function.put("name", "search");
    function.put("arguments", "{}");

    JSONObject toolCall = new JSONObject();
    toolCall.put("id", "call_1");
    toolCall.put("type", "function");
    toolCall.put("function", function);

    JSONArray toolCalls = new JSONArray();
    toolCalls.put(toolCall);

    JSONObject message = new JSONObject();
    message.put("role", "assistant");
    message.put("content", "I'll search for you");
    message.put("tool_calls", toolCalls);

    List<Part> parts = SarvamBaseLM.openAiMessageToParts(message);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).functionCall()).isPresent();
    assertThat(parts.get(0).functionCall().get().name()).hasValue("search");
  }

  @Test
  public void openAiMessageToParts_emptyToolCalls_fallsBackToContent() {
    JSONObject message = new JSONObject();
    message.put("role", "assistant");
    message.put("content", "Here are the results");
    message.put("tool_calls", new JSONArray());

    List<Part> parts = SarvamBaseLM.openAiMessageToParts(message);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("Here are the results");
  }

  // ========== Constructor / config tests ==========

  @Test
  public void constructor_setsModelName() {
    SarvamBaseLM llm = new SarvamBaseLM("sarvam-m");
    assertThat(llm.model()).isEqualTo("sarvam-m");
  }

  @Test
  public void constructor_withBaseUrl_setsModelName() {
    SarvamBaseLM llm = new SarvamBaseLM("sarvam-m", "https://custom.api.com/v1");
    assertThat(llm.model()).isEqualTo("sarvam-m");
  }

  @Test
  public void connect_returnsGenericLlmConnection() {
    SarvamBaseLM llm = new SarvamBaseLM("sarvam-m");
    LlmRequest request = LlmRequest.builder().build();

    BaseLlmConnection connection = llm.connect(request);

    assertThat(connection).isInstanceOf(GenericLlmConnection.class);
  }
}
