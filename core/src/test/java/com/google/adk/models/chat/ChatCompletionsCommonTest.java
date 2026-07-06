package com.google.adk.models.chat;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ChatCompletionsCommonTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  public void parseToolCallArguments_withValidJson() throws Exception {
    String json = "{\"pr_number\": 1042, \"reason\": \"review\"}";
    ImmutableMap<String, Object> args =
        ChatCompletionsCommon.parseToolCallArguments(json, objectMapper);
    assertThat(args).hasSize(2);
    assertThat(args.get("pr_number")).isEqualTo(1042);
    assertThat(args.get("reason")).isEqualTo("review");
    assertThat(args).isInstanceOf(ImmutableMap.class);
  }

  @Test
  public void parseToolCallArguments_withEmptyString() throws Exception {
    Map<String, Object> args = ChatCompletionsCommon.parseToolCallArguments("", objectMapper);
    assertThat(args).isEmpty();
  }

  @Test
  public void parseToolCallArguments_withNullString() throws Exception {
    Map<String, Object> args = ChatCompletionsCommon.parseToolCallArguments(null, objectMapper);
    assertThat(args).isEmpty();
  }

  @Test
  public void parseToolCallArguments_withWhitespaceString() throws Exception {
    Map<String, Object> args = ChatCompletionsCommon.parseToolCallArguments("   ", objectMapper);
    assertThat(args).isEmpty();
  }

  @Test
  public void parseToolCallArguments_withInvalidJson_throwsException() {
    assertThrows(
        JsonProcessingException.class,
        () -> ChatCompletionsCommon.parseToolCallArguments("none", objectMapper));

    assertThrows(
        JsonProcessingException.class,
        () -> ChatCompletionsCommon.parseToolCallArguments("{bad_json:", objectMapper));
  }

  @Test
  public void parseToolCallArguments_withLiteralNullString_throwsException() {
    JsonProcessingException exception =
        assertThrows(
            JsonProcessingException.class,
            () -> ChatCompletionsCommon.parseToolCallArguments("null", objectMapper));
    assertThat(exception)
        .hasMessageThat()
        .contains("JSON literal 'null' is not a valid JSON object for tool call arguments");
  }
}
