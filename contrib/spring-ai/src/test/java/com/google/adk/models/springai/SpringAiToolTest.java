/*
 * Copyright 2026 Google LLC
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.events.EventActions;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

class SpringAiToolTest {

  private static ToolCallback callbackWithSchema(String name, String inputSchema) {
    ToolCallback callback = mock(ToolCallback.class);
    when(callback.getToolDefinition())
        .thenReturn(
            ToolDefinition.builder()
                .name(name)
                .description(name + " tool")
                .inputSchema(inputSchema)
                .build());
    when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
    when(callback.call(any(), any())).thenReturn("{\"ok\":true}");
    return callback;
  }

  @Test
  void declaration_unparseableInputSchema_fallsBackToEmptyObjectSchema() {
    SpringAiTool tool = new SpringAiTool(callbackWithSchema("broken", "{not json"));

    FunctionDeclaration declaration = tool.declaration().orElseThrow();

    assertThat(tool.name()).isEqualTo("broken");
    assertThat(declaration.parametersJsonSchema())
        .contains(Map.of("type", "object", "properties", Map.of()));
    assertThat(tool.runAsync(Map.of(), null).blockingGet()).containsEntry("ok", true);
  }

  @Test
  void getTools_oneCallbackWithUnparseableSchema_stillListsEveryTool() {
    SpringAiToolset toolset =
        new SpringAiToolset(
            () ->
                new ToolCallback[] {
                  callbackWithSchema("broken", "[1, 2]"),
                  callbackWithSchema("valid", "{\"type\":\"object\",\"properties\":{}}")
                });

    List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();

    assertThat(tools).extracting(BaseTool::name).containsExactly("broken", "valid");
  }

  @Test
  void declaration_blankInputSchema_hasNoParameters() {
    ToolDefinition definition = mock(ToolDefinition.class);
    when(definition.name()).thenReturn("ping");
    when(definition.description()).thenReturn("");
    when(definition.inputSchema()).thenReturn(" ");
    ToolCallback callback = mock(ToolCallback.class);
    when(callback.getToolDefinition()).thenReturn(definition);

    FunctionDeclaration declaration = new SpringAiTool(callback).declaration().orElseThrow();

    assertThat(declaration.parametersJsonSchema()).isEmpty();
    assertThat(declaration.parameters()).isEmpty();
  }

  @Test
  void runAsync_passesAdkToolContextAndMapsReturnDirect() {
    AtomicReference<org.springframework.ai.chat.model.ToolContext> captured =
        new AtomicReference<>();
    ToolCallback callback = mock(ToolCallback.class);
    when(callback.getToolDefinition())
        .thenReturn(
            ToolDefinition.builder()
                .name("direct")
                .description("Direct tool")
                .inputSchema("{\"type\":\"object\"}")
                .build());
    when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(true).build());
    when(callback.call(any(), any()))
        .thenAnswer(
            invocation -> {
              captured.set(invocation.getArgument(1));
              return "42 is the answer";
            });
    ToolContext adkContext = mock(ToolContext.class);
    EventActions actions = EventActions.builder().build();
    when(adkContext.actions()).thenReturn(actions);

    Map<String, Object> result =
        new SpringAiTool(callback).runAsync(Map.of("k", "v"), adkContext).blockingGet();

    assertThat(result).containsEntry("result", "42 is the answer");
    assertThat(captured.get().getContext())
        .containsEntry(ToolConverter.ADK_TOOL_CONTEXT_KEY, adkContext);
    assertThat(actions.skipSummarization()).contains(true);
  }
}
