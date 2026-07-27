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

  // Schema exercising items / enum / additionalProperties — the fields that would be dropped
  // if the bridge routed through parameters(Schema.fromJson) instead of parametersJsonSchema(Map).
  private static final String RICH_SCHEMA =
      "{"
          + "\"type\":\"object\","
          + "\"properties\":{"
          + "\"city\":{\"type\":\"string\"},"
          + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
          + "\"mode\":{\"type\":\"string\",\"enum\":[\"fast\",\"slow\"]}"
          + "},"
          + "\"additionalProperties\":false,"
          + "\"required\":[\"city\"]"
          + "}";

  @Test
  void declaration_setsParametersJsonSchema_notParameters_toPreserveFullSchema() {
    ToolCallback callback = mockCallback("weather", "Returns weather for a city", RICH_SCHEMA);

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);

    assertThat(tool.name()).isEqualTo("weather");
    assertThat(tool.description()).isEqualTo("Returns weather for a city");
    assertThat(tool.declaration()).isPresent();
    // Faithful branch: schema goes through parametersJsonSchema (preserves
    // items/enum/additionalProperties);
    // the lossy parameters(Schema) branch is intentionally not used.
    assertThat(tool.declaration().get().parametersJsonSchema()).isPresent();
    assertThat(tool.declaration().get().parameters()).isEmpty();
    @SuppressWarnings("unchecked")
    Map<String, Object> stored =
        (Map<String, Object>) tool.declaration().get().parametersJsonSchema().get();
    assertThat(stored).containsKey("additionalProperties");
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) stored.get("properties");
    assertThat(props).containsKeys("city", "tags", "mode");
  }

  @Test
  void runAsync_serializesArgs_andParsesJsonObjectResponse() {
    ToolCallback callback = mockCallback("weather", "desc", RICH_SCHEMA);
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
    ToolCallback callback = mockCallback("ping", "desc", RICH_SCHEMA);
    when(callback.call(anyString())).thenReturn("\"pong\"");

    Map<String, Object> result =
        new SpringAiToolCallbackBackedAdkTool(callback).runAsync(Map.of(), null).blockingGet();

    assertThat(result).containsKey("result");
  }

  @Test
  void runAsync_emptyResponse_yieldsEmptyMap() {
    ToolCallback callback = mockCallback("noop", "desc", RICH_SCHEMA);
    when(callback.call(anyString())).thenReturn("");

    Map<String, Object> result =
        new SpringAiToolCallbackBackedAdkTool(callback).runAsync(Map.of(), null).blockingGet();

    assertThat(result).isEmpty();
  }

  @Test
  void runAsync_nullArgs_sendsEmptyObject() {
    ToolCallback callback = mockCallback("noop", "desc", RICH_SCHEMA);
    when(callback.call(anyString())).thenReturn("{}");

    new SpringAiToolCallbackBackedAdkTool(callback).runAsync(null, null).blockingGet();

    ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
    verify(callback).call(argsCaptor.capture());
    assertThat(argsCaptor.getValue()).isEqualTo("{}");
  }

  @Test
  void runAsync_whenCallbackThrows_returnsErrorMapWithMessage() {
    ToolCallback callback = mockCallback("boom", "desc", RICH_SCHEMA);
    when(callback.call(anyString())).thenThrow(new RuntimeException("upstream MCP timeout"));

    Map<String, Object> result =
        new SpringAiToolCallbackBackedAdkTool(callback).runAsync(Map.of(), null).blockingGet();

    assertThat(result).containsEntry("error", "upstream MCP timeout");
  }

  @Test
  void runAsync_whenCallbackThrowsWithoutMessage_fallsBackToExceptionClassName() {
    ToolCallback callback = mockCallback("boom", "desc", RICH_SCHEMA);
    when(callback.call(anyString())).thenThrow(new IllegalStateException());

    Map<String, Object> result =
        new SpringAiToolCallbackBackedAdkTool(callback).runAsync(Map.of(), null).blockingGet();

    assertThat(result).containsEntry("error", "IllegalStateException");
  }

  @Test
  void wrapAll_convertsEveryCallback() {
    ToolCallback a = mockCallback("a", "tool a", RICH_SCHEMA);
    ToolCallback b = mockCallback("b", "tool b", RICH_SCHEMA);

    var wrapped = SpringAiToolCallbackBackedAdkTool.wrapAll(List.of(a, b));

    assertThat(wrapped).hasSize(2);
    assertThat(wrapped.get(0).name()).isEqualTo("a");
    assertThat(wrapped.get(1).name()).isEqualTo("b");
  }

  @Test
  void invalidSchema_leavesParametersUnset_withoutThrowingOrEmittingDegradedSchema() {
    ToolCallback callback = mockCallback("malformed", "desc", "not valid json {[");

    SpringAiToolCallbackBackedAdkTool tool = new SpringAiToolCallbackBackedAdkTool(callback);

    assertThat(tool.declaration()).isPresent();
    // Unparseable schema → both fields empty (rather than the old fallback that emitted a
    // double-encoded quoted-string parametersJsonSchema).
    assertThat(tool.declaration().get().parametersJsonSchema()).isEmpty();
    assertThat(tool.declaration().get().parameters()).isEmpty();
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
