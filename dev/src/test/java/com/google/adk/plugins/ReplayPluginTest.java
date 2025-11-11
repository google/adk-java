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
package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ReplayPlugin}.
 *
 * <p>Note: The core comparison logic is tested in {@link LlmRequestComparatorTest}. These tests
 * verify the plugin's callback behavior and replay functionality.
 */
class ReplayPluginTest {

  @TempDir Path tempDir;

  private ReplayPlugin plugin;
  private Session mockSession;
  private ConcurrentHashMap<String, Object> sessionState;
  private State state;

  @BeforeEach
  void setUp() {
    plugin = new ReplayPlugin();
    mockSession = mock(Session.class);
    sessionState = new ConcurrentHashMap<>();
    state = new State(sessionState);

    when(mockSession.state()).thenReturn(sessionState);
  }

  @Test
  void beforeModelCallback_withMatchingRecording_returnsRecordedResponse() throws Exception {
    // Setup: Create a minimal recording file
    Path recordingsFile = tempDir.resolve("generated-recordings.yaml");
    Files.writeString(
        recordingsFile,
        """
        recordings:
          - user_message_index: 0
            agent_index: 0
            agent_name: "test_agent"
            llm_recording:
              llm_request:
                model: "gemini-2.0-flash"
                contents:
                  - role: "user"
                    parts:
                      - text: "Hello"
              llm_response:
                content:
                  role: "model"
                  parts:
                    - text: "Recorded response"
        """);

    // Step 1: Setup replay config
    sessionState.put(
        "_adk_replay_config", ImmutableMap.of("dir", tempDir.toString(), "user_message_index", 0));

    // Step 2: Call beforeRunCallback to load recordings
    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.session()).thenReturn(mockSession);
    when(invocationContext.invocationId()).thenReturn("test-invocation");

    plugin.beforeRunCallback(invocationContext).blockingGet();

    // Step 3: Call beforeModelCallback with matching request
    CallbackContext callbackContext = mock(CallbackContext.class);
    when(callbackContext.state()).thenReturn(state);
    when(callbackContext.invocationId()).thenReturn("test-invocation");
    when(callbackContext.agentName()).thenReturn("test_agent");

    LlmRequest request =
        LlmRequest.builder()
            .model("gemini-2.0-flash")
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(Part.builder().text("Hello").build())
                        .build()))
            .build();

    // Step 4: Verify expected response is returned
    var result = plugin.beforeModelCallback(callbackContext, request).blockingGet();

    assertThat(result).isNotNull();
    assertThat(result.content()).isPresent();
    assertThat(result.content().get().text()).isEqualTo("Recorded response");
  }

  @Test
  void beforeModelCallback_requestMismatch_throwsVerificationError() throws Exception {
    // Setup: Create recording with different model
    Path recordingsFile = tempDir.resolve("generated-recordings.yaml");
    Files.writeString(
        recordingsFile,
        """
        recordings:
          - user_message_index: 0
            agent_index: 0
            agent_name: "test_agent"
            llm_recording:
              llm_request:
                model: "gemini-1.5-pro"
                contents:
                  - role: "user"
                    parts:
                      - text: "Hello"
        """);

    // Step 1: Setup replay config
    sessionState.put(
        "_adk_replay_config", ImmutableMap.of("dir", tempDir.toString(), "user_message_index", 0));

    // Step 2: Load recordings
    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.session()).thenReturn(mockSession);
    when(invocationContext.invocationId()).thenReturn("test-invocation");
    plugin.beforeRunCallback(invocationContext).blockingGet();

    // Step 3: Call with mismatched request
    CallbackContext callbackContext = mock(CallbackContext.class);
    when(callbackContext.state()).thenReturn(state);
    when(callbackContext.invocationId()).thenReturn("test-invocation");
    when(callbackContext.agentName()).thenReturn("test_agent");

    LlmRequest request =
        LlmRequest.builder()
            .model("gemini-2.0-flash") // Different model
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(Part.builder().text("Hello").build())
                        .build()))
            .build();

    // Step 4: Verify verification error is thrown
    assertThrows(
        ReplayVerificationError.class,
        () -> plugin.beforeModelCallback(callbackContext, request).blockingGet());
  }

  @Test
  void beforeToolCallback_withMatchingRecording_returnsRecordedResponse() throws Exception {
    // Setup: Create recording with tool call
    Path recordingsFile = tempDir.resolve("generated-recordings.yaml");
    Files.writeString(
        recordingsFile,
        """
        recordings:
          - user_message_index: 0
            agent_index: 0
            agent_name: "test_agent"
            tool_recording:
              tool_call:
                name: "test_tool"
                args:
                  param1: "value1"
                  param2: 42
              tool_response:
                name: "test_tool"
                response:
                  result: "success"
                  data: "recorded data"
        """);

    // Step 1: Setup replay config
    sessionState.put(
        "_adk_replay_config", ImmutableMap.of("dir", tempDir.toString(), "user_message_index", 0));

    // Step 2: Load recordings
    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.session()).thenReturn(mockSession);
    when(invocationContext.invocationId()).thenReturn("test-invocation");
    plugin.beforeRunCallback(invocationContext).blockingGet();

    // Step 3: Call beforeToolCallback with matching tool call
    BaseTool mockTool = mock(BaseTool.class);
    when(mockTool.name()).thenReturn("test_tool");
    // Mock runAsync to avoid NullPointerException during tool execution
    when(mockTool.runAsync(any(), any())).thenReturn(Single.just(Map.of()));

    ToolContext toolContext = mock(ToolContext.class);
    when(toolContext.state()).thenReturn(state);
    when(toolContext.invocationId()).thenReturn("test-invocation");
    when(toolContext.agentName()).thenReturn("test_agent");

    Map<String, Object> toolArgs = ImmutableMap.of("param1", "value1", "param2", 42);

    // Step 4: Verify expected response is returned
    var result = plugin.beforeToolCallback(mockTool, toolArgs, toolContext).blockingGet();

    assertThat(result).isNotNull();
    assertThat(result).containsEntry("result", "success");
    assertThat(result).containsEntry("data", "recorded data");
  }

  @Test
  void beforeToolCallback_toolNameMismatch_throwsVerificationError() throws Exception {
    // Setup: Create recording
    Path recordingsFile = tempDir.resolve("generated-recordings.yaml");
    Files.writeString(
        recordingsFile,
        """
        recordings:
          - user_message_index: 0
            agent_index: 0
            agent_name: "test_agent"
            tool_recording:
              tool_call:
                name: "expected_tool"
                args:
                  param: "value"
        """);

    // Step 1: Setup replay config
    sessionState.put(
        "_adk_replay_config", ImmutableMap.of("dir", tempDir.toString(), "user_message_index", 0));

    // Step 2: Load recordings
    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.session()).thenReturn(mockSession);
    when(invocationContext.invocationId()).thenReturn("test-invocation");
    plugin.beforeRunCallback(invocationContext).blockingGet();

    // Step 3: Call with wrong tool name
    BaseTool mockTool = mock(BaseTool.class);
    when(mockTool.name()).thenReturn("actual_tool"); // Wrong name

    ToolContext toolContext = mock(ToolContext.class);
    when(toolContext.state()).thenReturn(state);
    when(toolContext.invocationId()).thenReturn("test-invocation");
    when(toolContext.agentName()).thenReturn("test_agent");

    // Step 4: Verify verification error is thrown
    assertThrows(
        ReplayVerificationError.class,
        () ->
            plugin
                .beforeToolCallback(mockTool, ImmutableMap.of("param", "value"), toolContext)
                .blockingGet());
  }

  @Test
  void beforeToolCallback_toolArgsMismatch_throwsVerificationError() throws Exception {
    // Setup: Create recording
    Path recordingsFile = tempDir.resolve("generated-recordings.yaml");
    Files.writeString(
        recordingsFile,
        """
        recordings:
          - user_message_index: 0
            agent_index: 0
            agent_name: "test_agent"
            tool_recording:
              tool_call:
                name: "test_tool"
                args:
                  param: "expected_value"
        """);

    // Step 1: Setup replay config
    sessionState.put(
        "_adk_replay_config", ImmutableMap.of("dir", tempDir.toString(), "user_message_index", 0));

    // Step 2: Load recordings
    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.session()).thenReturn(mockSession);
    when(invocationContext.invocationId()).thenReturn("test-invocation");
    plugin.beforeRunCallback(invocationContext).blockingGet();

    // Step 3: Call with wrong args
    BaseTool mockTool = mock(BaseTool.class);
    when(mockTool.name()).thenReturn("test_tool");

    ToolContext toolContext = mock(ToolContext.class);
    when(toolContext.state()).thenReturn(state);
    when(toolContext.invocationId()).thenReturn("test-invocation");
    when(toolContext.agentName()).thenReturn("test_agent");

    // Step 4: Verify verification error is thrown
    assertThrows(
        ReplayVerificationError.class,
        () ->
            plugin
                .beforeToolCallback(
                    mockTool, ImmutableMap.of("param", "actual_value"), toolContext) // Wrong value
                .blockingGet());
  }
}
