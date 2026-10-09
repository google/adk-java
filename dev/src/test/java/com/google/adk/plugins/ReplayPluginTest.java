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
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Assumptions;
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

  private Path replayRoot;
  private ReplayPlugin plugin;
  private Session mockSession;
  private ConcurrentHashMap<String, Object> sessionState;
  private State state;

  @BeforeEach
  void setUp() throws Exception {
    // toRealPath: @TempDir can sit behind a symlink, e.g. /var -> /private/var on macOS.
    replayRoot = Files.createDirectory(tempDir.resolve("root")).toRealPath();
    plugin = new ReplayPlugin("adk_replay", replayRoot);
    mockSession = mock(Session.class);
    sessionState = new ConcurrentHashMap<>();
    state = new State(sessionState);

    when(mockSession.state()).thenReturn(sessionState);
  }

  @Test
  void beforeModelCallback_withMatchingRecording_returnsRecordedResponse() throws Exception {
    // Setup: Create a minimal recording file
    Path recordingsFile = replayRoot.resolve("generated-recordings.yaml");
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
              llm_responses:
                - content:
                    role: "model"
                    parts:
                      - text: "Recorded response"
        """);

    // Step 1: Setup replay config
    sessionState.put(
        "_adk_replay_config",
        ImmutableMap.of("dir", replayRoot.toString(), "user_message_index", 0));

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

    var request =
        LlmRequest.builder()
            .model("gemini-2.0-flash")
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(Part.builder().text("Hello").build())
                        .build()));

    // Step 4: Verify expected response is returned
    var result = plugin.beforeModelCallback(callbackContext, request).blockingGet();

    assertThat(result).isNotNull();
    assertThat(result.content()).isPresent();
    assertThat(result.content().get().text()).isEqualTo("Recorded response");
  }

  @Test
  void beforeModelCallback_requestMismatch_returnsEmpty() throws Exception {
    // Setup: Create recording with different model
    Path recordingsFile = replayRoot.resolve("generated-recordings.yaml");
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
        "_adk_replay_config",
        ImmutableMap.of("dir", replayRoot.toString(), "user_message_index", 0));

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

    var request =
        LlmRequest.builder()
            .model("gemini-2.0-flash") // Different model
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(Part.builder().text("Hello").build())
                        .build()));

    // Step 4: Verify result is empty
    var result = plugin.beforeModelCallback(callbackContext, request).blockingGet();
    assertThat(result).isNull();
  }

  @Test
  void beforeToolCallback_withMatchingRecording_returnsRecordedResponse() throws Exception {
    // Setup: Create recording with tool call
    Path recordingsFile = replayRoot.resolve("generated-recordings.yaml");
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
        "_adk_replay_config",
        ImmutableMap.of("dir", replayRoot.toString(), "user_message_index", 0));

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
  void beforeToolCallback_toolNameMismatch_returnsEmpty() throws Exception {
    // Setup: Create recording
    Path recordingsFile = replayRoot.resolve("generated-recordings.yaml");
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
        "_adk_replay_config",
        ImmutableMap.of("dir", replayRoot.toString(), "user_message_index", 0));

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

    // Step 4: Verify result is empty
    var result =
        plugin
            .beforeToolCallback(mockTool, ImmutableMap.of("param", "value"), toolContext)
            .blockingGet();
    assertThat(result).isNull();
  }

  @Test
  void beforeToolCallback_toolArgsMismatch_returnsEmpty() throws Exception {
    // Setup: Create recording
    Path recordingsFile = replayRoot.resolve("generated-recordings.yaml");
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
        "_adk_replay_config",
        ImmutableMap.of("dir", replayRoot.toString(), "user_message_index", 0));

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

    // Step 4: Verify result is empty
    var result =
        plugin
            .beforeToolCallback(
                mockTool, ImmutableMap.of("param", "actual_value"), toolContext) // Wrong value
            .blockingGet();
    assertThat(result).isNull();
  }

  @Test
  void beforeRunCallback_relativeCaseDirInsideRoot_loadsRecordings() throws Exception {
    Path caseDir = Files.createDirectory(replayRoot.resolve("case"));
    Files.writeString(caseDir.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", "case", "user_message_index", 0));

    plugin.beforeRunCallback(newInvocationContext()).blockingGet();

    assertThat(replayedResponseText(plugin)).isEqualTo("Recorded response");
  }

  @Test
  void beforeRunCallback_symlinkedCaseDirInsideRoot_loadsRecordings() throws Exception {
    Path caseDir = Files.createDirectory(replayRoot.resolve("actual_case"));
    Files.writeString(caseDir.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    createSymbolicLinkOrSkip(replayRoot.resolve("case"), caseDir);
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", "case", "user_message_index", 0));

    plugin.beforeRunCallback(newInvocationContext()).blockingGet();

    assertThat(replayedResponseText(plugin)).isEqualTo("Recorded response");
  }

  @Test
  void beforeRunCallback_caseDirTraversesOutsideRoot_throws() throws Exception {
    Path outsideDir = Files.createDirectory(tempDir.resolve("outside"));
    Files.writeString(outsideDir.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put(
        "_adk_replay_config", ImmutableMap.of("dir", "../outside", "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("resolves outside the replay root");
  }

  @Test
  void beforeRunCallback_absoluteCaseDirOutsideRoot_throws() throws Exception {
    Path outsideDir = Files.createDirectory(tempDir.resolve("outside"));
    Files.writeString(outsideDir.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put(
        "_adk_replay_config",
        ImmutableMap.of("dir", outsideDir.toString(), "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("resolves outside the replay root");
  }

  @Test
  void beforeRunCallback_symlinkedCaseDirOutsideRoot_throws() throws Exception {
    Path outsideDir = Files.createDirectory(tempDir.resolve("outside"));
    Files.writeString(outsideDir.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    createSymbolicLinkOrSkip(replayRoot.resolve("case"), outsideDir);
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", "case", "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("resolves outside the replay root");
  }

  @Test
  void beforeRunCallback_symlinkedRecordingsFileOutsideRoot_throws() throws Exception {
    Path outsideDir = Files.createDirectory(tempDir.resolve("outside"));
    Path outsideFile = outsideDir.resolve("generated-recordings.yaml");
    Files.writeString(outsideFile, MINIMAL_RECORDINGS);
    Path caseDir = Files.createDirectory(replayRoot.resolve("case"));
    createSymbolicLinkOrSkip(caseDir.resolve("generated-recordings.yaml"), outsideFile);
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", "case", "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("resolves outside the replay root");
  }

  @Test
  void beforeRunCallback_caseDirIsSiblingSharingRootPrefix_throws() throws Exception {
    // "root" is a lexical prefix of "rootsibling", so a string comparison would let this through.
    Path siblingDir = Files.createDirectory(tempDir.resolve("rootsibling"));
    Files.writeString(siblingDir.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put(
        "_adk_replay_config",
        ImmutableMap.of("dir", siblingDir.toString(), "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("resolves outside the replay root");
  }

  @Test
  void beforeRunCallback_missingRecordingsFileInsideRoot_throws() {
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", "case", "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("Recordings file not found");
  }

  @Test
  void beforeRunCallback_replayRootFromSystemProperty_loadsRecordings() throws Exception {
    Files.writeString(replayRoot.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    String previous = System.getProperty("adk.replay.root");
    System.setProperty("adk.replay.root", replayRoot.toString());
    try {
      ReplayPlugin pluginFromProperty = new ReplayPlugin();
      sessionState.put("_adk_replay_config", ImmutableMap.of("dir", ".", "user_message_index", 0));

      pluginFromProperty.beforeRunCallback(newInvocationContext()).blockingGet();

      assertThat(replayedResponseText(pluginFromProperty)).isEqualTo("Recorded response");
    } finally {
      if (previous == null) {
        System.clearProperty("adk.replay.root");
      } else {
        System.setProperty("adk.replay.root", previous);
      }
    }
  }

  @Test
  void beforeRunCallback_nonMapReplayConfig_leavesReplayOff() {
    sessionState.put("_adk_replay_config", "/etc/passwd");

    plugin.beforeRunCallback(newInvocationContext()).blockingGet();

    // Replay stays off, so the model call falls through instead of hitting missing replay state.
    assertThat(replayedResponse(plugin)).isNull();
  }

  @Test
  void beforeRunCallback_userMessageIndexNotANumber_throws() throws Exception {
    Files.writeString(replayRoot.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put(
        "_adk_replay_config", ImmutableMap.of("dir", ".", "user_message_index", "zero"));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("'user_message_index' must be a number");
  }

  @Test
  void beforeRunCallback_userMessageIndexNotAWholeNumber_throws() throws Exception {
    Files.writeString(replayRoot.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", ".", "user_message_index", 1.5));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("must be a whole number");
  }

  @Test
  void beforeRunCallback_userMessageIndexAsLong_loadsRecordings() throws Exception {
    Files.writeString(replayRoot.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", ".", "user_message_index", 0L));

    plugin.beforeRunCallback(newInvocationContext()).blockingGet();

    assertThat(replayedResponseText(plugin)).isEqualTo("Recorded response");
  }

  @Test
  void beforeRunCallback_caseDirNotAString_throws() throws Exception {
    Files.writeString(replayRoot.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put("_adk_replay_config", ImmutableMap.of("dir", 1, "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> plugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains("'dir' must be a string");
  }

  @Test
  void beforeRunCallback_rootReachedThroughSymlink_loadsRecordings() throws Exception {
    // The conformance dev server configures its root through a symlink, so both spellings have to
    // work: the symlinked root, and the canonical one the caller may send back.
    Files.writeString(replayRoot.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    Path linkedRoot = tempDir.resolve("linked_root");
    createSymbolicLinkOrSkip(linkedRoot, replayRoot);
    ReplayPlugin linkedPlugin = new ReplayPlugin("adk_replay", linkedRoot);

    sessionState.put(
        "_adk_replay_config",
        ImmutableMap.of("dir", linkedRoot.toString(), "user_message_index", 0));
    linkedPlugin.beforeRunCallback(newInvocationContext()).blockingGet();
    assertThat(replayedResponseText(linkedPlugin)).isEqualTo("Recorded response");

    sessionState.put(
        "_adk_replay_config",
        ImmutableMap.of("dir", replayRoot.toString(), "user_message_index", 0));
    linkedPlugin.beforeRunCallback(newInvocationContext()).blockingGet();
    assertThat(replayedResponseText(linkedPlugin)).isEqualTo("Recorded response");
  }

  @Test
  void beforeRunCallback_workingDirectoryRoot_rejectsCaseDirOutsideIt() throws Exception {
    // The root the plugin falls back to when neither knob is set. Driven through the constructor
    // so the test does not depend on the environment it runs in.
    ReplayPlugin workingDirPlugin = new ReplayPlugin("adk_replay", Paths.get(""));
    Files.writeString(replayRoot.resolve("generated-recordings.yaml"), MINIMAL_RECORDINGS);
    sessionState.put(
        "_adk_replay_config",
        ImmutableMap.of("dir", replayRoot.toString(), "user_message_index", 0));

    ReplayConfigError error =
        assertThrows(
            ReplayConfigError.class,
            () -> workingDirPlugin.beforeRunCallback(newInvocationContext()).blockingGet());
    assertThat(error).hasMessageThat().contains(Paths.get("").toAbsolutePath().toString());
  }

  @Test
  void configuredReplayRoot_prefersPropertyOverEnvironment() {
    assertThat(ReplayPlugin.configuredReplayRoot("/from-property", "/from-env"))
        .isEqualTo("/from-property");
    assertThat(ReplayPlugin.configuredReplayRoot(null, "/from-env")).isEqualTo("/from-env");
    assertThat(ReplayPlugin.configuredReplayRoot("", "/from-env")).isEqualTo("/from-env");
    assertThat(ReplayPlugin.configuredReplayRoot(null, null)).isNull();
    assertThat(ReplayPlugin.configuredReplayRoot("", "")).isNull();
  }

  private InvocationContext newInvocationContext() {
    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.session()).thenReturn(mockSession);
    when(invocationContext.invocationId()).thenReturn("test-invocation");
    return invocationContext;
  }

  /**
   * Windows without developer mode refuses symlinks with an IOException, not the documented one.
   */
  private static void createSymbolicLinkOrSkip(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("File system does not support symlinks: " + e.getMessage());
    }
  }

  /** Runs the model callback against the loaded recordings, or null when replay is off. */
  private LlmResponse replayedResponse(ReplayPlugin plugin) {
    CallbackContext callbackContext = mock(CallbackContext.class);
    when(callbackContext.state()).thenReturn(state);
    when(callbackContext.invocationId()).thenReturn("test-invocation");
    when(callbackContext.agentName()).thenReturn("test_agent");
    return plugin
        .beforeModelCallback(callbackContext, LlmRequest.builder().model("gemini-2.0-flash"))
        .blockingGet();
  }

  private String replayedResponseText(ReplayPlugin plugin) {
    LlmResponse response = replayedResponse(plugin);
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    return response.content().get().text();
  }

  private static final String MINIMAL_RECORDINGS =
      """
      recordings:
        - user_message_index: 0
          agent_index: 0
          agent_name: "test_agent"
          llm_recording:
            llm_request:
              model: "gemini-2.0-flash"
            llm_responses:
              - content:
                  role: "model"
                  parts:
                    - text: "Recorded response"
      """;
}
