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

package com.google.adk.plugins.debuglogging;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Covers the wiring: that each hook records the entry adk-python records, that none of them changes
 * the run, and that a hook arriving without an invocation is dropped rather than thrown.
 *
 * <p>Contexts are mocked in the style of {@code LoggingPluginTest}, this package's own precedent
 * for a plugin test. Assertions read the written YAML back, so they cover the whole path from hook
 * to file rather than the plugin's internal bookkeeping.
 */
@RunWith(JUnit4.class)
public class DebugLoggingPluginTest {

  private static final String INVOCATION_ID = "invocation_id";
  private static final String AGENT_NAME = "agent_name";

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Mock private InvocationContext invocationContext;
  @Mock private BaseAgent agent;
  @Mock private CallbackContext callbackContext;
  @Mock private BaseTool tool;
  @Mock private ToolContext toolContext;

  private final Session session =
      Session.builder("session_id").appName("app_name").userId("user_id").build();
  private final Content userMessage =
      Content.builder()
          .role("user")
          .parts(ImmutableList.of(Part.builder().text("hello").build()))
          .build();
  private final LlmRequest.Builder llmRequest =
      LlmRequest.builder().model("gemini-2.0-flash").contents(ImmutableList.of());
  private final LlmResponse llmResponse = LlmResponse.builder().build();
  private final Event event =
      Event.builder()
          .id("event_id")
          .author(AGENT_NAME)
          .actions(EventActions.builder().build())
          .build();
  private final ImmutableMap<String, Object> toolArgs = ImmutableMap.of("query", "socks");
  private final ImmutableMap<String, Object> toolResult = ImmutableMap.of("status", "ok");
  private final Throwable error = new IllegalStateException("boom");

  private Path tracePath;

  @Before
  public void setUp() throws Exception {
    tracePath = tempFolder.newFolder("traces").toPath().resolve("adk_debug.yaml");

    when(invocationContext.invocationId()).thenReturn(INVOCATION_ID);
    when(invocationContext.session()).thenReturn(session);
    when(invocationContext.agent()).thenReturn(agent);
    when(invocationContext.userId()).thenReturn("user_id");
    when(invocationContext.branch()).thenReturn(Optional.empty());
    when(agent.name()).thenReturn(AGENT_NAME);

    when(callbackContext.invocationId()).thenReturn(INVOCATION_ID);
    when(callbackContext.agentName()).thenReturn(AGENT_NAME);
    when(callbackContext.branch()).thenReturn(Optional.empty());

    when(toolContext.invocationId()).thenReturn(INVOCATION_ID);
    when(toolContext.agentName()).thenReturn(AGENT_NAME);
    when(toolContext.functionCallId()).thenReturn(Optional.of("call-1"));
    when(tool.name()).thenReturn("lookup_order");
  }

  private DebugLoggingPlugin plugin() {
    return new DebugLoggingPlugin("debug_logging_plugin", tracePath, true, true);
  }

  /**
   * Drives every hook in the order a real run fires them, and closes the invocation.
   *
   * <p>{@code onUserMessageCallback} goes <b>first</b>, which is not an arbitrary choice: {@code
   * Runner.runAsync} invokes it before {@code beforeRunCallback}. A test that fired them the other
   * way round would never exercise the case this plugin handles.
   */
  private void runEveryHook(DebugLoggingPlugin plugin) {
    plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.beforeAgentCallback(agent, callbackContext).blockingGet();
    plugin.beforeModelCallback(callbackContext, llmRequest).blockingGet();
    plugin.afterModelCallback(callbackContext, llmResponse).blockingGet();
    plugin.onModelErrorCallback(callbackContext, llmRequest, error).blockingGet();
    plugin.beforeToolCallback(tool, toolArgs, toolContext).blockingGet();
    plugin.afterToolCallback(tool, toolArgs, toolContext, toolResult).blockingGet();
    plugin.onToolErrorCallback(tool, toolArgs, toolContext, error).blockingGet();
    plugin.onEventCallback(invocationContext, event).blockingGet();
    plugin.afterAgentCallback(agent, callbackContext).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();
  }

  private ImmutableList<Map<String, Object>> readDocuments() throws Exception {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    try (MappingIterator<Map<String, Object>> documents =
        yaml.readerFor(Map.class).readValues(tracePath.toFile())) {
      return ImmutableList.copyOf(documents.readAll());
    }
  }

  private ImmutableList<String> entryTypes() throws Exception {
    List<?> entries = (List<?>) readDocuments().get(0).get("entries");
    return entries.stream()
        .map(entry -> (String) ((Map<?, ?>) entry).get("entry_type"))
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void everyHook_recordsItsEntry_inTheOrderTheyFired() throws Exception {
    runEveryHook(plugin());

    assertThat(entryTypes())
        .containsExactly(
            "user_message",
            "invocation_start",
            "agent_start",
            "llm_request",
            "llm_response",
            "llm_error",
            "tool_call",
            "tool_response",
            "tool_error",
            "event",
            "agent_end",
            "session_state_snapshot",
            "invocation_end")
        .inOrder();
  }

  /**
   * The port's deliberate difference from adk-python in what gets recorded.
   *
   * <p>The user message arrives before {@code beforeRunCallback} has opened the invocation, so the
   * first hook to arrive opens it. Without that, the entry would be filed against an invocation
   * that does not exist yet and dropped.
   */
  @Test
  public void userMessage_arrivingBeforeTheRunStarts_isKeptRatherThanDropped() throws Exception {
    DebugLoggingPlugin plugin = plugin();

    plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryTypes()).containsAtLeast("user_message", "invocation_start").inOrder();
    assertThat(entryData("user_message").toString()).contains("hello");
  }

  /** The whole safety argument for this plugin: it observes, and cannot alter, a run. */
  @Test
  public void everyHook_returnsEmpty_soNothingIsAltered() {
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();

    assertThat(plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet()).isNull();
    assertThat(plugin.beforeAgentCallback(agent, callbackContext).blockingGet()).isNull();
    assertThat(plugin.afterAgentCallback(agent, callbackContext).blockingGet()).isNull();
    assertThat(plugin.beforeModelCallback(callbackContext, llmRequest).blockingGet()).isNull();
    assertThat(plugin.afterModelCallback(callbackContext, llmResponse).blockingGet()).isNull();
    assertThat(plugin.onModelErrorCallback(callbackContext, llmRequest, error).blockingGet())
        .isNull();
    assertThat(plugin.beforeToolCallback(tool, toolArgs, toolContext).blockingGet()).isNull();
    assertThat(plugin.afterToolCallback(tool, toolArgs, toolContext, toolResult).blockingGet())
        .isNull();
    assertThat(plugin.onToolErrorCallback(tool, toolArgs, toolContext, error).blockingGet())
        .isNull();
    assertThat(plugin.onEventCallback(invocationContext, event).blockingGet()).isNull();
  }

  @Test
  public void toolEntries_carryTheToolNameCallIdAndArguments() throws Exception {
    runEveryHook(plugin());

    Map<?, ?> toolCall = entryData("tool_call");
    assertThat(toolCall.get("tool_name")).isEqualTo("lookup_order");
    assertThat(toolCall.get("function_call_id")).isEqualTo("call-1");
    assertThat(toolCall.get("args")).isEqualTo(ImmutableMap.of("query", "socks"));
  }

  @Test
  public void llmError_recordsTheExceptionTypeAndModel() throws Exception {
    runEveryHook(plugin());

    Map<?, ?> llmError = entryData("llm_error");
    assertThat(llmError.get("error_type")).isEqualTo("IllegalStateException");
    assertThat(llmError.get("error_message")).isEqualTo("boom");
    assertThat(llmError.get("model")).isEqualTo("gemini-2.0-flash");
  }

  @Test
  public void includeSessionState_whenOff_omitsTheSnapshot() throws Exception {
    runEveryHook(new DebugLoggingPlugin("debug_logging_plugin", tracePath, false, true));

    assertThat(entryTypes()).doesNotContain("session_state_snapshot");
    assertThat(entryTypes()).contains("invocation_end");
  }

  @Test
  public void includeSystemInstruction_whenOff_notesItsPresenceWithoutTheText() throws Exception {
    llmRequest.config(
        com.google.genai.types.GenerateContentConfig.builder()
            .systemInstruction(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("be terse").build()))
                    .build())
            .build());

    runEveryHook(new DebugLoggingPlugin("debug_logging_plugin", tracePath, true, false));

    Map<?, ?> config = (Map<?, ?>) entryData("llm_request").get("config");
    assertThat(config.get("has_system_instruction")).isEqualTo(true);
    assertThat(Files.readString(tracePath)).doesNotContain("be terse");
  }

  /** A hook can outlive its invocation; losing the state must cost a log line, not the run. */
  @Test
  public void hooksWithoutAnInvocation_areDroppedRatherThanThrown() throws Exception {
    DebugLoggingPlugin plugin = plugin();

    plugin.onEventCallback(invocationContext, event).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(Files.exists(tracePath)).isFalse();
  }

  /** Upstream drops the state in a {@code finally}; a second write must find nothing left. */
  @Test
  public void afterRun_dropsTheInvocation_soASecondRunWritesNothingMore() throws Exception {
    DebugLoggingPlugin plugin = plugin();
    runEveryHook(plugin);

    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(readDocuments()).hasSize(1);
  }

  /**
   * The payload of the first entry of that type — by type, never by index, so adding an entry ahead
   * of it cannot silently repoint an assertion at a different entry.
   */
  private Map<?, ?> entryData(String entryType) throws Exception {
    List<?> entries = (List<?>) readDocuments().get(0).get("entries");
    return entries.stream()
        .map(entry -> (Map<?, ?>) entry)
        .filter(entry -> entryType.equals(entry.get("entry_type")))
        .findFirst()
        .map(entry -> (Map<?, ?>) entry.get("data"))
        .orElseThrow();
  }
}
