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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LlmRequest}. */
@RunWith(JUnit4.class)
public final class LlmRequestTest {
  private static class TestTool extends BaseTool {
    TestTool(String name) {
      super(name, "this is the greatest tool");
    }
  }

  private static final TestTool TOOL_1 = new TestTool("tool_1");
  private static final TestTool TOOL_2 = new TestTool("tool_2");

  @Test
  public void builder_defaultValues_setsSensibleDefaults() {
    LlmRequest request = LlmRequest.builder().build();

    assertThat(request.model()).isEmpty();
    assertThat(request.contents()).isEmpty();
    assertThat(request.config()).isEmpty();
    assertThat(request.liveConnectConfig()).isNotNull();
    assertThat(request.liveConnectConfig().temperature()).isEmpty();
    assertThat(request.liveConnectConfig().systemInstruction()).isEmpty();
    assertThat(request.tools()).isEmpty();
  }

  @Test
  public void appendInstructions_noExistingConfig_addsInstructionCorrectly() {
    String instruction = "Be concise.";
    LlmRequest request =
        LlmRequest.builder().appendInstructions(ImmutableList.of(instruction)).build();

    assertThat(request.config()).isPresent();
    Content systemInstruction = request.config().get().systemInstruction().get();
    assertThat(systemInstruction.role()).hasValue("user");
    assertThat(systemInstruction.parts().get()).hasSize(1);
    assertThat(systemInstruction.parts().get().get(0).text()).hasValue(instruction);

    assertThat(request.liveConnectConfig().systemInstruction()).isPresent();
    Content liveSystemInstruction = request.liveConnectConfig().systemInstruction().get();
    assertThat(liveSystemInstruction.role()).hasValue("user");
    assertThat(liveSystemInstruction.parts().get()).hasSize(1);
    assertThat(liveSystemInstruction.parts().get().get(0).text()).hasValue(instruction);
  }

  @Test
  public void appendInstructions_existingConfigNoInstruction_addsInstructionPreservingConfig() {
    String instruction = "Be concise.";
    GenerateContentConfig initialConfig = GenerateContentConfig.builder().temperature(0.8f).build();
    LlmRequest request =
        LlmRequest.builder()
            .config(initialConfig)
            .appendInstructions(ImmutableList.of(instruction))
            .build();

    assertThat(request.config()).isPresent();
    assertThat(request.config().get().temperature()).isEqualTo(initialConfig.temperature());
    Content systemInstruction = request.config().get().systemInstruction().get();
    assertThat(systemInstruction.parts().get()).hasSize(1);
    assertThat(systemInstruction.parts().get().get(0).text()).hasValue(instruction);

    assertThat(request.liveConnectConfig().systemInstruction()).isPresent();
    Content liveSystemInstruction = request.liveConnectConfig().systemInstruction().get();
    assertThat(liveSystemInstruction.role()).hasValue("user");
    assertThat(liveSystemInstruction.parts().get()).hasSize(1);
    assertThat(liveSystemInstruction.parts().get().get(0).text()).hasValue(instruction);
  }

  @Test
  public void appendInstructions_existingInstruction_appendsNewInstructionCorrectly() {
    String initialInstructionText = "Be polite.";
    Content initialSystemInstruction =
        Content.builder()
            .role("system")
            .parts(ImmutableList.of(Part.builder().text(initialInstructionText).build()))
            .build();
    GenerateContentConfig initialConfig =
        GenerateContentConfig.builder().systemInstruction(initialSystemInstruction).build();

    String newInstructionText = "Be concise.";
    LlmRequest request =
        LlmRequest.builder()
            .config(initialConfig)
            .appendInstructions(ImmutableList.of(newInstructionText))
            .build();

    assertThat(request.config()).isPresent();
    Content systemInstruction = request.config().get().systemInstruction().get();
    assertThat(systemInstruction.role()).hasValue("system");
    assertThat(systemInstruction.parts().get()).hasSize(2);
    assertThat(systemInstruction.parts().get().get(0).text()).hasValue(initialInstructionText);
    assertThat(systemInstruction.parts().get().get(1).text()).hasValue(newInstructionText);

    assertThat(request.liveConnectConfig().systemInstruction()).isPresent();
    Content liveSystemInstruction = request.liveConnectConfig().systemInstruction().get();
    assertThat(liveSystemInstruction.role()).hasValue("user");
    assertThat(liveSystemInstruction.parts().get()).hasSize(1);
    assertThat(liveSystemInstruction.parts().get().get(0).text()).hasValue(newInstructionText);
  }

  @Test
  public void
      appendInstructions_liveConnectConfigWithExistingInstruction_appendsNewInstructionCorrectly() {
    String initialLiveInstructionText = "Live: Be cautious.";
    Content initialLiveSystemInstruction =
        Content.builder()
            .role("system") // Custom role
            .parts(ImmutableList.of(Part.builder().text(initialLiveInstructionText).build()))
            .build();
    LiveConnectConfig initialLiveConfig =
        LiveConnectConfig.builder().systemInstruction(initialLiveSystemInstruction).build();

    String newInstructionText = "Live: Be fast.";
    LlmRequest request =
        LlmRequest.builder()
            .liveConnectConfig(initialLiveConfig) // Set initial live config
            .appendInstructions(ImmutableList.of(newInstructionText))
            .build();

    // Assertions for liveConnectConfig
    assertThat(request.liveConnectConfig().systemInstruction()).isPresent();
    Content liveSystemInstruction = request.liveConnectConfig().systemInstruction().get();
    assertThat(liveSystemInstruction.role()).hasValue("system"); // Role preserved
    assertThat(liveSystemInstruction.parts().get()).hasSize(2);
    assertThat(liveSystemInstruction.parts().get().get(0).text())
        .hasValue(initialLiveInstructionText);
    assertThat(liveSystemInstruction.parts().get().get(1).text()).hasValue(newInstructionText);

    // Assertions for main config (should get the new instruction with default role)
    assertThat(request.config()).isPresent();
    Content mainSystemInstruction = request.config().get().systemInstruction().get();
    assertThat(mainSystemInstruction.role()).hasValue("user");
    assertThat(mainSystemInstruction.parts().get()).hasSize(1);
    assertThat(mainSystemInstruction.parts().get().get(0).text()).hasValue(newInstructionText);
  }

  @Test
  public void appendInstructions_emptyList_doesNotModifyBuilderState() {
    GenerateContentConfig initialConfig = GenerateContentConfig.builder().temperature(0.8f).build();
    LiveConnectConfig initialLiveConfig =
        LiveConnectConfig.builder()
            .systemInstruction(
                Content.builder().parts(ImmutableList.of(Part.fromText("Initial live"))).build())
            .build();
    LlmRequest initialRequestState =
        LlmRequest.builder().config(initialConfig).liveConnectConfig(initialLiveConfig).build();
    LlmRequest finalRequestState =
        initialRequestState.toBuilder().appendInstructions(ImmutableList.of()).build();

    assertThat(finalRequestState.config()).isEqualTo(initialRequestState.config());
    assertThat(finalRequestState.liveConnectConfig())
        .isEqualTo(initialRequestState.liveConnectConfig());
  }

  @Test
  public void appendInstructions_multipleInstructions_appendsAllInOrder() {
    String instruction1 = "First instruction.";
    String instruction2 = "Second instruction.";
    LlmRequest request =
        LlmRequest.builder()
            .appendInstructions(ImmutableList.of(instruction1, instruction2))
            .build();

    assertThat(request.config()).isPresent();
    Content systemInstruction = request.config().get().systemInstruction().get();
    assertThat(systemInstruction.parts().get()).hasSize(2);
    assertThat(systemInstruction.parts().get().get(0).text()).hasValue(instruction1);
    assertThat(systemInstruction.parts().get().get(1).text()).hasValue(instruction2);

    assertThat(request.liveConnectConfig().systemInstruction()).isPresent();
    Content liveSystemInstruction = request.liveConnectConfig().systemInstruction().get();
    assertThat(liveSystemInstruction.role()).hasValue("user");
    assertThat(liveSystemInstruction.parts().get()).hasSize(2);
    assertThat(liveSystemInstruction.parts().get().get(0).text()).hasValue(instruction1);
    assertThat(liveSystemInstruction.parts().get().get(1).text()).hasValue(instruction2);
  }

  @Test
  public void appendTools_noExistingTools_addsToolsCorrectly() {

    LlmRequest request = LlmRequest.builder().appendTools(ImmutableList.of(TOOL_1, TOOL_2)).build();

    assertThat(request.tools()).hasSize(2);
    assertThat(request.tools()).containsEntry(TOOL_1.name(), TOOL_1);
    assertThat(request.tools()).containsEntry(TOOL_2.name(), TOOL_2);
  }

  @Test
  public void appendTools_existingToolsNoOverlap_mergesToolsCorrectly() {

    LlmRequest initialRequest =
        LlmRequest.builder().tools(ImmutableMap.of(TOOL_1.name(), TOOL_1)).build();
    LlmRequest finalRequest =
        initialRequest.toBuilder().appendTools(ImmutableList.of(TOOL_2)).build();

    assertThat(finalRequest.tools()).hasSize(2);
    assertThat(finalRequest.tools()).containsEntry(TOOL_1.name(), TOOL_1);
    assertThat(finalRequest.tools()).containsEntry(TOOL_2.name(), TOOL_2);
  }

  @Test
  public void appendTools_existingToolsWithOverlap_throwsIllegalArgumentException() {

    LlmRequest initialRequest =
        LlmRequest.builder().tools(ImmutableMap.of(TOOL_1.name(), TOOL_1)).build();

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> initialRequest.toBuilder().appendTools(ImmutableList.of(TOOL_1)).build());

    assertThat(exception).hasMessageThat().contains("Duplicate tool name: " + TOOL_1.name());
  }

  @Test
  public void appendTools_emptyList_doesNotModifyTools() {

    ImmutableMap<String, BaseTool> initialTools = ImmutableMap.of(TOOL_1.name(), TOOL_1);
    LlmRequest initialRequest = LlmRequest.builder().tools(initialTools).build();
    LlmRequest finalRequest = initialRequest.toBuilder().appendTools(ImmutableList.of()).build();

    assertThat(finalRequest.tools()).isEqualTo(initialTools);
  }

  @Test
  public void outputSchema_noExistingConfig_setsSchemaAndJsonMimeType() {

    Schema schema = Schema.builder().type("STRING").description("A simple string output").build();
    LlmRequest request = LlmRequest.builder().outputSchema(schema).build();

    assertThat(request.config()).isPresent();
    assertThat(request.config().get().responseSchema()).hasValue(schema);
    assertThat(request.config().get().responseMimeType()).hasValue("application/json");
  }

  @Test
  public void outputSchema_existingConfig_setsSchemaAndJsonMimeTypePreservingOthers() {

    GenerateContentConfig initialConfig = GenerateContentConfig.builder().temperature(0.9f).build();
    Schema schema = Schema.builder().type("INTEGER").description("An integer output").build();
    LlmRequest request = LlmRequest.builder().config(initialConfig).outputSchema(schema).build();

    assertThat(request.config()).isPresent();
    assertThat(request.config().get().temperature()).isEqualTo(initialConfig.temperature());
    assertThat(request.config().get().responseSchema()).hasValue(schema);
    assertThat(request.config().get().responseMimeType()).hasValue("application/json");
  }

  @Test
  public void getSystemInstruction_whenNoConfig_returnsEmpty() {
    LlmRequest request = LlmRequest.builder().build();
    Optional<String> systemText = request.getFirstSystemInstruction();
    assertThat(systemText).isEmpty();
  }

  @Test
  public void getSystemInstruction_whenPresent_returnsText() {
    String instruction = "This is the system instruction.";
    LlmRequest request =
        LlmRequest.builder().appendInstructions(ImmutableList.of(instruction)).build();

    Optional<String> systemText = request.getFirstSystemInstruction();
    assertThat(systemText).hasValue(instruction);
  }

  @Test
  public void getSystemInstructions_whenPresent_returnsList() {
    String instruction1 = "Do A.";
    String instruction2 = "Then Do B.";

    LlmRequest request =
        LlmRequest.builder()
            .appendInstructions(ImmutableList.of(instruction1, instruction2))
            .build();
    assertThat(request.getSystemInstructions())
        .containsExactly(instruction1, instruction2)
        .inOrder();
  }
}
