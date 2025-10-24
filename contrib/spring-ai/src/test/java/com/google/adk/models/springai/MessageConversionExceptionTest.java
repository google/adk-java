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
package com.google.adk.models.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class MessageConversionExceptionTest {

  @Test
  void testBasicConstructors() {
    // Test message-only constructor
    MessageConversionException ex1 = new MessageConversionException("Test message");
    assertThat(ex1.getMessage()).isEqualTo("Test message");
    assertThat(ex1.getCause()).isNull();

    // Test message and cause constructor
    Throwable cause = new RuntimeException("Original cause");
    MessageConversionException ex2 = new MessageConversionException("Test with cause", cause);
    assertThat(ex2.getMessage()).isEqualTo("Test with cause");
    assertThat(ex2.getCause()).isEqualTo(cause);

    // Test cause-only constructor
    MessageConversionException ex3 = new MessageConversionException(cause);
    assertThat(ex3.getCause()).isEqualTo(cause);
  }

  @Test
  void testJsonParsingFailedFactory() {
    JsonProcessingException jsonException = new JsonProcessingException("JSON error") {};

    MessageConversionException ex =
        MessageConversionException.jsonParsingFailed("tool call arguments", jsonException);

    assertThat(ex.getMessage()).isEqualTo("Failed to parse JSON for tool call arguments");
    assertThat(ex.getCause()).isEqualTo(jsonException);
  }

  @Test
  void testInvalidMessageStructureFactory() {
    MessageConversionException ex =
        MessageConversionException.invalidMessageStructure("missing required field");

    assertThat(ex.getMessage()).isEqualTo("Invalid message structure: missing required field");
    assertThat(ex.getCause()).isNull();
  }

  @Test
  void testUnsupportedContentTypeFactory() {
    MessageConversionException ex = MessageConversionException.unsupportedContentType("video/mp4");

    assertThat(ex.getMessage()).isEqualTo("Unsupported content type: video/mp4");
    assertThat(ex.getCause()).isNull();
  }

  @Test
  void testExceptionInMessageConverter() {
    // This test verifies that MessageConverter throws the custom exception
    MessageConverter converter = new MessageConverter(new ObjectMapper());

    // Create an AssistantMessage with invalid JSON in tool call arguments
    AssistantMessage.ToolCall invalidToolCall =
        new AssistantMessage.ToolCall("id123", "function", "test_function", "invalid json{");
    AssistantMessage assistantMessage =
        new AssistantMessage("Test", java.util.Map.of(), java.util.List.of(invalidToolCall));

    // This should throw MessageConversionException due to invalid JSON
    Exception exception =
        assertThrows(
            Exception.class,
            () -> {
              // Use reflection to access private method for testing
              java.lang.reflect.Method method =
                  MessageConverter.class.getDeclaredMethod(
                      "convertAssistantMessageToContent", AssistantMessage.class);
              method.setAccessible(true);
              method.invoke(converter, assistantMessage);
            });

    // When using reflection, the exception is wrapped in InvocationTargetException
    assertThat(exception).isInstanceOf(java.lang.reflect.InvocationTargetException.class);
    Throwable cause = exception.getCause();
    assertThat(cause).isInstanceOf(MessageConversionException.class);
    assertThat(cause.getMessage()).contains("Failed to parse JSON for tool call arguments");
    assertThat(cause.getCause()).isInstanceOf(JsonProcessingException.class);
  }
}
