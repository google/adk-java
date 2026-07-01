/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.adk.models.springai.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class SpringAiToolCallbackBackedAdkToolTest {

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";

  @Test
  void declaration_isBuiltFromToolDefinition() {
    ToolCallback callback = mockCallback("weather", "Returns weather for a city", SCHEMA);

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);

    assertThat(tool.name()).isEqualTo("weather");
    assertThat(tool.description()).isEqualTo("Returns weather for a city");
    assertThat(tool.declaration()).isPresent();
    assertThat(tool.declaration().get().name()).hasValue("weather");
    assertThat(tool.declaration().get().description()).hasValue("Returns weather for a city");
    assertThat(tool.declaration().get().parameters()).isPresent();
  }

  @Test
  void runAsync_serializesArgs_andParsesJsonObjectResponse() throws Exception {
    ToolCallback callback = mockCallback("weather", "desc", SCHEMA);
    when(callback.call(anyString())).thenReturn("{\"forecast\":\"sunny\",\"temp\":22}");

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);
    Map<String, Object> result = tool.runAsync(Map.of("city", "Paris"), null).blockingGet();

    assertThat(result).containsEntry("forecast", "sunny").containsEntry("temp", 22);

    ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
    verify(callback).call(argsCaptor.capture());
    assertThat(argsCaptor.getValue()).contains("\"city\":\"Paris\"");
  }

  @Test
  void runAsync_wrapsScalarResponse_underResultKey() {
    ToolCallback callback = mockCallback("ping", "desc", SCHEMA);
    when(callback.call(anyString())).thenReturn("\"pong\"");

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);
    Map<String, Object> result = tool.runAsync(Map.of(), null).blockingGet();

    assertThat(result).containsKey("result");
  }

  @Test
  void runAsync_emptyResponse_yieldsEmptyMap() {
    ToolCallback callback = mockCallback("noop", "desc", SCHEMA);
    when(callback.call(anyString())).thenReturn("");

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);
    Map<String, Object> result = tool.runAsync(Map.of(), null).blockingGet();

    assertThat(result).isEmpty();
  }

  @Test
  void runAsync_nullArgs_sendsEmptyObject() {
    ToolCallback callback = mockCallback("noop", "desc", SCHEMA);
    when(callback.call(anyString())).thenReturn("{}");

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);
    tool.runAsync(null, null).blockingGet();

    ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
    verify(callback).call(argsCaptor.capture());
    assertThat(argsCaptor.getValue()).isEqualTo("{}");
  }

  @Test
  void wrapAll_convertsEveryCallback() {
    ToolCallback a = mockCallback("a", "tool a", SCHEMA);
    ToolCallback b = mockCallback("b", "tool b", SCHEMA);

    var wrapped = SpringAiToolCallbackBackedAdkTool.wrapAll(List.of(a, b));

    assertThat(wrapped).hasSize(2);
    assertThat(wrapped.get(0).name()).isEqualTo("a");
    assertThat(wrapped.get(1).name()).isEqualTo("b");
  }

  @Test
  void invalidSchema_fallsBackToRawJsonSchema_withoutThrowing() {
    ToolCallback callback = mockCallback("malformed", "desc", "not valid json {[");

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);

    assertThat(tool.declaration()).isPresent();
    assertThat(tool.declaration().get().parametersJsonSchema()).isPresent();
  }

  private static ToolCallback mockCallback(String name, String description, String inputSchema) {
    ToolCallback cb = mock(ToolCallback.class);
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .build();
    when(cb.getToolDefinition()).thenReturn(def);
    return cb;
  }
}
