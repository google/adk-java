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

package com.google.adk.plugins;

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createSubAgent;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.nCopies;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.genai.types.Blob;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Flowable;
import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Drives the plugin's hooks and reads the written YAML back. */
@RunWith(JUnit4.class)
public class DebugLoggingPluginTest {

  private static final String INVOCATION_ID = "invocation_id";
  private static final String AGENT_NAME = "agent_name";
  private static final String APP_NAME = "app_name";
  private static final String USER_ID = "user_id";
  private static final String QUOTED = "\"%s\"";
  private static final Instant NOW = Instant.parse("2026-10-23T18:30:00Z");
  private static final int WRITERS = 8;
  private static final String INVOCATION_ID_FORMAT = "inv-%d";
  private static final int RACE_TRIALS = 10;
  private static final String TRIAL_PATH = "race-%d/adk_debug.yaml";
  private static final String CIRCULAR = "<circular reference>";

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private final BaseAgent agent = createSubAgent(AGENT_NAME);
  private final BaseTool tool = new LookupOrderTool();

  private final Session session =
      Session.builder("session_id").appName(APP_NAME).userId(USER_ID).build();
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
  private InvocationContext invocationContext;
  private CallbackContext callbackContext;
  private ToolContext toolContext;

  @Before
  public void setUp() throws Exception {
    // The directory does not exist yet, so every test that writes the file also covers its
    // creation.
    tracePath = tempFolder.getRoot().toPath().resolve("traces/adk_debug.yaml");

    useSession(session);
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

  @Test
  public void userMessage_arrivingBeforeTheRunStarts_isKeptRatherThanDropped() throws Exception {
    DebugLoggingPlugin plugin = plugin();

    plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryTypes()).containsAtLeast("user_message", "invocation_start").inOrder();
    assertThat(entryData("user_message").toString()).contains("hello");
  }

  @Test
  public void userMessage_arrivingAfterTheRunStarts_isRecorded() throws Exception {
    DebugLoggingPlugin plugin = plugin();

    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryTypes()).containsAtLeast("invocation_start", "user_message").inOrder();
    assertThat(entryData("user_message")).containsExactly("content", textContent("user", "hello"));
  }

  @Test
  public void everyHook_returnsEmpty_soNothingIsAltered() {
    DebugLoggingPlugin plugin = plugin();

    assertThat(plugin.beforeRunCallback(invocationContext).blockingGet()).isNull();
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
  public void toolCall_carriesTheToolNameCallIdAndArguments() throws Exception {
    runEveryHook(plugin());

    Map<?, ?> toolCall = entryData("tool_call");
    assertThat(toolCall.get("tool_name")).isEqualTo("lookup_order");
    assertThat(toolCall.get("function_call_id")).isEqualTo("call-1");
    assertThat(toolCall.get("args")).isEqualTo(ImmutableMap.of("query", "socks"));
  }

  @Test
  public void toolResponseAndToolError_carryTheirFullData() throws Exception {
    runEveryHook(plugin());

    assertThat(entryData("tool_response"))
        .containsExactly(
            "tool_name", "lookup_order",
            "function_call_id", "call-1",
            "result", ImmutableMap.of("status", "ok"))
        .inOrder();
    assertThat(entryData("tool_error"))
        .containsExactly(
            "tool_name", "lookup_order",
            "function_call_id", "call-1",
            "args", ImmutableMap.of("query", "socks"),
            "error_type", "IllegalStateException",
            "error_message", "boom")
        .inOrder();
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
    runEveryHook(
        DebugLoggingPlugin.builder().outputPath(tracePath).includeSessionState(false).build());

    assertThat(entryTypes()).doesNotContain("session_state_snapshot");
    assertThat(entryTypes()).contains("invocation_end");
  }

  @Test
  public void includeSystemInstruction_whenOff_notesItsPresenceWithoutTheText() throws Exception {
    llmRequest.config(
        GenerateContentConfig.builder()
            .systemInstruction(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("be terse").build()))
                    .build())
            .build());

    runEveryHook(
        DebugLoggingPlugin.builder().outputPath(tracePath).includeSystemInstruction(false).build());

    Map<?, ?> config = (Map<?, ?>) entryData("llm_request").get("config");
    assertThat(config.get("has_system_instruction")).isEqualTo(true);
    assertThat(Files.readString(tracePath)).doesNotContain("be terse");
  }

  @Test
  public void includeSystemInstruction_whenOn_recordsTheTextAndTheGenerationSettings()
      throws Exception {
    llmRequest.config(
        GenerateContentConfig.builder()
            .systemInstruction(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("be terse").build()))
                    .build())
            .temperature(0.5f)
            .topP(0.25f)
            .topK(40f)
            .maxOutputTokens(256)
            .responseMimeType("application/json")
            .responseSchema(Schema.builder().type("OBJECT").build())
            .build());

    runEveryHook(plugin());

    assertThat(entryData("llm_request").get("config"))
        .isEqualTo(
            ImmutableMap.of(
                "system_instruction",
                ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "be terse"))),
                "temperature",
                0.5,
                "top_p",
                0.25,
                "top_k",
                40.0,
                "max_output_tokens",
                256,
                "response_mime_type",
                "application/json",
                "has_response_schema",
                true));
  }

  @Test
  public void hooksWithoutAnInvocation_areDroppedRatherThanThrown() throws Exception {
    DebugLoggingPlugin plugin = plugin();

    plugin.onEventCallback(invocationContext, event).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(Files.exists(tracePath)).isFalse();
  }

  @Test
  public void afterRun_dropsTheInvocation_soASecondRunWritesNothingMore() throws Exception {
    DebugLoggingPlugin plugin = plugin();
    runEveryHook(plugin);

    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(readDocuments()).hasSize(1);
  }

  @Test
  public void defaultName_isDebugLoggingPlugin() {
    assertThat(new DebugLoggingPlugin().getName()).isEqualTo("debug_logging_plugin");
  }

  @Test
  public void builder_rejectsANullPathOrName() {
    assertThrows(NullPointerException.class, () -> DebugLoggingPlugin.builder().outputPath(null));
    assertThrows(NullPointerException.class, () -> DebugLoggingPlugin.builder().name(null));
  }

  @Test
  public void entries_writeTheirKeysInOrder_andOmitAnAbsentAgentName() throws Exception {
    runEveryHook(plugin());

    assertThat(entry("tool_call").keySet())
        .containsExactly("timestamp", "entry_type", "invocation_id", "agent_name", "data")
        .inOrder();
    assertThat(entry("user_message").keySet())
        .containsExactly("timestamp", "entry_type", "invocation_id", "data")
        .inOrder();
  }

  @Test
  public void sessionWithoutAppNameOrUserId_omitsThoseHeaderKeys() throws Exception {
    useSession(Session.builder("session_id").build());

    runEveryHook(plugin());

    assertThat(readDocuments().get(0).keySet())
        .containsExactly("invocation_id", "session_id", "start_time", "entries")
        .inOrder();
  }

  @Test
  public void llmRequest_withoutToolNamesOrSettings_omitsToolsAndConfig() throws Exception {
    llmRequest
        .contents(ImmutableList.of(Content.builder().role("user").build()))
        .config(
            GenerateContentConfig.builder()
                .tools(
                    ImmutableList.of(
                        Tool.builder().googleSearch(GoogleSearch.builder().build()).build(),
                        Tool.builder()
                            .functionDeclarations(
                                ImmutableList.of(
                                    FunctionDeclaration.builder().description("unnamed").build()))
                            .build()))
                .build());

    runEveryHook(plugin());

    assertThat(entryData("llm_request"))
        .containsExactly(
            "model",
            "gemini-2.0-flash",
            "content_count",
            1,
            "contents",
            ImmutableList.of(ImmutableMap.of("role", "user", "parts", ImmutableList.of())))
        .inOrder();
  }

  @Test
  public void event_withAnEmptyLongRunningToolIdSet_omitsTheKey() throws Exception {
    runEvent(event.toBuilder().longRunningToolIds(ImmutableSet.of()).build());

    assertThat(entryData("event")).doesNotContainKey("long_running_tool_ids");
  }

  @Test
  public void bareFileNameOnAPosixFileSystem_isCreatedOwnerOnly() throws Exception {
    try (FileSystem unix =
        Jimfs.newFileSystem(
            Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build())) {
      Path bareName = unix.getPath("adk_debug.yaml");

      runEveryHook(DebugLoggingPlugin.builder().outputPath(bareName).build());

      assertThat(Files.getPosixFilePermissions(bareName))
          .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      assertThat(Files.readString(bareName)).startsWith("---");
    }
  }

  @Test
  public void builderName_setsThePluginName() {
    assertThat(DebugLoggingPlugin.builder().name("trace").build().getName()).isEqualTo("trace");
  }

  @Test
  public void multiLineText_isWrittenAsALiteralBlock() throws Exception {
    ImmutableMap<String, Object> result = ImmutableMap.of("summary", "line one\nline two");
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterToolCallback(tool, toolArgs, toolContext, result).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(Files.readString(tracePath)).contains("summary: |-");
    assertThat(entryData("tool_response").get("result")).isEqualTo(result);
  }

  @Test
  public void unwritablePath_doesNotThrow() throws Exception {
    File blocker = tempFolder.newFile("not-a-directory");
    DebugLoggingPlugin plugin =
        DebugLoggingPlugin.builder()
            .outputPath(blocker.toPath().resolve("nested/adk_debug.yaml"))
            .build();

    runEveryHook(plugin);

    assertThat(blocker.length()).isEqualTo(0);
  }

  @Test
  public void concurrentInvocations_eachAppendOneIntactDocument() throws Exception {
    ImmutableList<String> invocationIds = invocationIds(WRITERS);

    runConcurrently(ImmutableList.copyOf(nCopies(WRITERS, plugin())), invocationIds);

    assertThat(readDocuments().stream().map(document -> document.get("invocation_id")))
        .containsExactlyElementsIn(invocationIds);
  }

  @Test
  public void twoPluginsOnOnePath_eachAppendTheirDocument() throws Exception {
    ImmutableList<String> invocationIds = invocationIds(2);
    for (int trial = 0; trial < RACE_TRIALS; trial++) {
      tracePath = tempFolder.getRoot().toPath().resolve(TRIAL_PATH.formatted(trial));

      runConcurrently(ImmutableList.of(plugin(), plugin()), invocationIds);

      assertThat(readDocuments().stream().map(document -> document.get("invocation_id")))
          .containsExactlyElementsIn(invocationIds);
    }
  }

  @Test
  public void restrictsOutputFileToOwner() throws Exception {
    runEveryHook(plugin());

    PosixFileAttributeView posixView =
        Files.getFileAttributeView(tracePath, PosixFileAttributeView.class);
    // POSIX permissions are only asserted on filesystems that support them.
    if (posixView != null) {
      assertThat(posixView.readAttributes().permissions())
          .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
  }

  @Test
  public void scalarLikeStrings_areQuotedAndReadBackAsStrings() throws Exception {
    ImmutableMap<String, Object> result =
        ImmutableMap.of(
            "inf",
            ".inf",
            "hex",
            "0x1F",
            "underscored",
            "1_000",
            "date",
            "2026-01-01",
            "int",
            "42");
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterToolCallback(tool, toolArgs, toolContext, result).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryData("tool_response").get("result")).isEqualTo(result);
    String text = Files.readString(tracePath);
    for (Object value : result.values()) {
      assertThat(text).contains(QUOTED.formatted(value));
    }
  }

  @Test
  public void runAsync_withThePluginRegistered_completesOnTheCallersThread() {
    LlmAgent rootAgent = createTestAgent(createTestLlm(createTextLlmResponse("done")));
    AtomicReference<Thread> completionThread = new AtomicReference<>();

    runAsync(rootAgent)
        .doOnComplete(() -> completionThread.set(Thread.currentThread()))
        .blockingSubscribe();

    assertThat(completionThread.get()).isSameInstanceAs(Thread.currentThread());
  }

  @Test
  public void runnerTurnWithAToolCall_isTracedEndToEnd() throws Exception {
    LlmAgent rootAgent =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse("call-1", "alpha", ImmutableMap.of()),
                    createTextLlmResponse("done")))
            .tools(ImmutableList.of(FunctionTool.create(DebugLoggingPluginTest.class, "alpha")))
            .build();
    Runner runner = runner(rootAgent);
    Session runSession = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();

    runner.runAsync(USER_ID, runSession.id(), userMessage).blockingSubscribe();

    assertThat(entryTypes())
        .containsExactly(
            "user_message",
            "invocation_start",
            "agent_start",
            "llm_request",
            "llm_response",
            "event",
            "tool_call",
            "tool_response",
            "event",
            "llm_request",
            "llm_response",
            "event",
            "agent_end",
            "session_state_snapshot",
            "invocation_end")
        .inOrder();
    assertThat(entryData("llm_request").get("contents"))
        .isEqualTo(ImmutableList.of(textContent("user", "hello")));
    assertThat(entryData("tool_call"))
        .containsAtLeast("tool_name", "alpha", "function_call_id", "call-1");
    Session stored =
        runner
            .sessionService()
            .getSession(APP_NAME, USER_ID, runSession.id(), Optional.empty())
            .blockingGet();
    assertThat(entryData("session_state_snapshot").get("event_count"))
        .isEqualTo(stored.immutableEvents().size());
  }

  @Test
  public void llmRequestTools_areRecordedInDeclarationOrder() throws Exception {
    LlmAgent rootAgent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("done")))
            .tools(
                ImmutableList.of(
                    FunctionTool.create(DebugLoggingPluginTest.class, "alpha"),
                    FunctionTool.create(DebugLoggingPluginTest.class, "beta"),
                    FunctionTool.create(DebugLoggingPluginTest.class, "gamma")))
            .build();

    runAsync(rootAgent).blockingSubscribe();

    assertThat(entryData("llm_request").get("tools"))
        .isEqualTo(ImmutableList.of("alpha", "beta", "gamma"));
  }

  @Test
  public void eventUsageMetadata_isWrittenBeforeGroundingMetadata() throws Exception {
    runEvent(
        event.toBuilder()
            .usageMetadata(
                GenerateContentResponseUsageMetadata.builder().promptTokenCount(1).build())
            .groundingMetadata(GroundingMetadata.builder().build())
            .build());

    assertThat(entryData("event").keySet())
        .containsExactly(
            "event_id", "author", "is_final_response", "usage_metadata", "has_grounding_metadata")
        .inOrder();
  }

  @Test
  public void toolResult_nullValue_isWrittenAsNull() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("discount", null);
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterToolCallback(tool, toolArgs, toolContext, result).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    Map<?, ?> written = (Map<?, ?>) entryData("tool_response").get("result");
    assertThat(written).containsExactly("discount", null);
  }

  @Test
  public void timestamps_areUtcInstantsFromTheInvocationsInstantSource() throws Exception {
    runEveryHook(plugin());

    Map<String, Object> document = readDocuments().get(0);
    assertThat(document.get("start_time")).isEqualTo(NOW.toString());
    List<?> entries = (List<?>) document.get("entries");
    for (Object entry : entries) {
      assertThat(((Map<?, ?>) entry).get("timestamp")).isEqualTo(NOW.toString());
    }
  }

  @Test
  public void escalate_whenFalse_isOmittedWithItsEmptyActionsBlock() throws Exception {
    runEvent(eventWithActions(EventActions.builder().escalate(false).build()));

    assertThat(entryData("event")).doesNotContainKey("actions");
  }

  @Test
  public void escalate_whenTrue_isRecorded() throws Exception {
    runEvent(eventWithActions(EventActions.builder().escalate(true).build()));

    Map<?, ?> actions = (Map<?, ?>) entryData("event").get("actions");
    assertThat(actions).containsExactly("escalate", true);
  }

  @Test
  public void functionCallAndResponse_withEmptyMaps_keepArgsAndResponse() throws Exception {
    Content content =
        Content.builder()
            .role("model")
            .parts(
                ImmutableList.of(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call-1")
                                .name("lookup_order")
                                .args(ImmutableMap.of())
                                .build())
                        .build(),
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("call-1")
                                .name("lookup_order")
                                .response(ImmutableMap.of())
                                .build())
                        .build()))
            .build();

    runEvent(event.toBuilder().content(content).build());

    List<?> parts = (List<?>) ((Map<?, ?>) entryData("event").get("content")).get("parts");
    Map<?, ?> functionCall = (Map<?, ?>) ((Map<?, ?>) parts.get(0)).get("function_call");
    Map<?, ?> functionResponse = (Map<?, ?>) ((Map<?, ?>) parts.get(1)).get("function_response");
    assertThat(functionCall.get("args")).isEqualTo(ImmutableMap.of());
    assertThat(functionResponse.get("response")).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void contentParts_recordEachKindAndOmitTheBytes() throws Exception {
    byte[] bytes = "secret".getBytes(UTF_8);
    Content content =
        Content.builder()
            .role("model")
            .parts(
                ImmutableList.of(
                    Part.builder().text("").build(),
                    Part.builder()
                        .inlineData(Blob.builder().mimeType("image/png").data(bytes).build())
                        .build(),
                    Part.builder()
                        .fileData(
                            FileData.builder()
                                .fileUri("gs://bucket/report.pdf")
                                .mimeType("application/pdf")
                                .build())
                        .build(),
                    Part.builder()
                        .codeExecutionResult(
                            CodeExecutionResult.builder().outcome("OUTCOME_OK").output("4").build())
                        .build(),
                    Part.builder()
                        .executableCode(
                            ExecutableCode.builder()
                                .language("PYTHON")
                                .code("print(2 + 2)")
                                .build())
                        .build()))
            .build();

    runEvent(event.toBuilder().content(content).build());

    assertThat(((Map<?, ?>) entryData("event").get("content")).get("parts"))
        .isEqualTo(
            ImmutableList.of(
                ImmutableMap.of(
                    "inline_data",
                    ImmutableMap.of("mime_type", "image/png", "_data_omitted", true)),
                ImmutableMap.of(
                    "file_data",
                    ImmutableMap.of(
                        "file_uri", "gs://bucket/report.pdf", "mime_type", "application/pdf")),
                ImmutableMap.of(
                    "code_execution_result",
                    ImmutableMap.of("outcome", "OUTCOME_OK", "output", "4")),
                ImmutableMap.of(
                    "executable_code",
                    ImmutableMap.of("language", "PYTHON", "code", "print(2 + 2)"))));
    assertThat(Files.readString(tracePath))
        .doesNotContain(Base64.getEncoder().encodeToString(bytes));
  }

  @Test
  public void llmResponse_recordsItsFlagsErrorFinishReasonAndModelVersion() throws Exception {
    LlmResponse response =
        LlmResponse.builder()
            .partial(true)
            .turnComplete(true)
            .errorCode(new FinishReason(FinishReason.Known.SAFETY))
            .errorMessage("blocked")
            .groundingMetadata(GroundingMetadata.builder().build())
            .finishReason(new FinishReason(FinishReason.Known.STOP))
            .modelVersion("gemini-2.0-flash-001")
            .build();
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterModelCallback(callbackContext, response).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryData("llm_response"))
        .containsExactly(
            "partial", true,
            "turn_complete", true,
            "error_code", "SAFETY",
            "error_message", "blocked",
            "has_grounding_metadata", true,
            "finish_reason", "STOP",
            "model_version", "gemini-2.0-flash-001")
        .inOrder();
  }

  @Test
  public void event_recordsItsFlagsBranchErrorAndLongRunningToolIds() throws Exception {
    runEvent(
        event.toBuilder()
            .partial(true)
            .turnComplete(true)
            .branch("root.child")
            .errorCode(new FinishReason(FinishReason.Known.SAFETY))
            .errorMessage("blocked")
            .longRunningToolIds(ImmutableSet.of("call-1"))
            .build());

    assertThat(entryData("event"))
        .containsAtLeast(
            "partial",
            true,
            "turn_complete",
            true,
            "branch",
            "root.child",
            "error_code",
            "SAFETY",
            "error_message",
            "blocked",
            "long_running_tool_ids",
            ImmutableList.of("call-1"))
        .inOrder();
  }

  @Test
  public void eventActions_recordDeltasTransferAndAuthConfigCount() throws Exception {
    runEvent(
        eventWithActions(
            EventActions.builder()
                .stateDelta(ImmutableMap.of("cart", "socks"))
                .artifactDelta(ImmutableMap.of("report.pdf", 1))
                .transferToAgent("billing")
                .requestedAuthConfigs(
                    ImmutableMap.of(
                        "call-1",
                        ImmutableMap.of("scheme", "oauth"),
                        "call-2",
                        ImmutableMap.of("scheme", "apiKey")))
                .build()));

    assertThat((Map<?, ?>) entryData("event").get("actions"))
        .containsExactly(
            "state_delta",
            ImmutableMap.of("cart", "socks"),
            "artifact_delta",
            ImmutableMap.of("report.pdf", 1),
            "transfer_to_agent",
            "billing",
            "requested_auth_configs",
            2)
        .inOrder();
  }

  @Test
  public void toolValues_areCopiedIntoPlainYamlValues() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("bytes", new byte[3]);
    result.put("nested", ImmutableMap.of("inner", ImmutableMap.of("key", "value")));
    result.put("numberKeys", ImmutableMap.of(7, "seven"));
    result.put("list", Arrays.asList("a", null));
    result.put("array", new Object[] {1, "b"});
    result.put("flag", true);
    result.put("ratio", 2.5);
    result.put("object", new Printable());
    result.put("unprintable", new Unprintable());
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterToolCallback(tool, toolArgs, toolContext, result).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat((Map<?, ?>) entryData("tool_response").get("result"))
        .containsExactly(
            "bytes",
            "<bytes: 3 bytes>",
            "nested",
            ImmutableMap.of("inner", ImmutableMap.of("key", "value")),
            "numberKeys",
            ImmutableMap.of("7", "seven"),
            "list",
            Arrays.asList("a", null),
            "array",
            ImmutableList.of(1, "b"),
            "flag",
            true,
            "ratio",
            2.5,
            "object",
            "printable",
            "unprintable",
            "<unserializable>")
        .inOrder();
  }

  @Test
  public void selfReferencingToolValues_areWrittenAsCircularReferences() throws Exception {
    Map<String, Object> branching = new LinkedHashMap<>();
    branching.put("left", branching);
    branching.put("right", branching);
    List<Object> list = new ArrayList<>();
    list.add(list);
    Object[] array = new Object[1];
    array[0] = array;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("self", result);
    result.put("map", branching);
    result.put("list", list);
    result.put("array", array);

    runToolResponse(result);

    assertThat(entryData("tool_response").get("result"))
        .isEqualTo(
            ImmutableMap.of(
                "self",
                CIRCULAR,
                "map",
                ImmutableMap.of("left", CIRCULAR, "right", CIRCULAR),
                "list",
                ImmutableList.of(CIRCULAR),
                "array",
                ImmutableList.of(CIRCULAR)));
  }

  @Test
  public void sharedToolValue_isWrittenInFullEachTime() throws Exception {
    ImmutableMap<String, Object> shared = ImmutableMap.of("sku", "socks");

    runToolResponse(ImmutableMap.of("first", shared, "second", ImmutableList.of(shared)));

    assertThat(entryData("tool_response").get("result"))
        .isEqualTo(ImmutableMap.of("first", shared, "second", ImmutableList.of(shared)));
  }

  @Test
  public void nonAsciiText_isWrittenUnescaped() throws Exception {
    String text = "café 東京 ✓";
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin
        .afterToolCallback(tool, toolArgs, toolContext, ImmutableMap.of("text", text))
        .blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(Files.readString(tracePath)).contains(QUOTED.formatted(text));
  }

  @Test
  public void usageMetadata_responseHasFourCountsAndEventHasThree() throws Exception {
    GenerateContentResponseUsageMetadata usage =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(1)
            .candidatesTokenCount(2)
            .totalTokenCount(3)
            .cachedContentTokenCount(4)
            .build();
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin
        .afterModelCallback(callbackContext, LlmResponse.builder().usageMetadata(usage).build())
        .blockingGet();
    plugin
        .onEventCallback(invocationContext, event.toBuilder().usageMetadata(usage).build())
        .blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryData("llm_response").get("usage_metadata"))
        .isEqualTo(
            ImmutableMap.of(
                "prompt_token_count", 1,
                "candidates_token_count", 2,
                "total_token_count", 3,
                "cached_content_token_count", 4));
    assertThat(entryData("event").get("usage_metadata"))
        .isEqualTo(
            ImmutableMap.of(
                "prompt_token_count", 1, "candidates_token_count", 2, "total_token_count", 3));
  }

  @Test
  public void capturesLlmRequestAndResponse() throws Exception {
    LlmRequest.Builder request =
        LlmRequest.builder()
            .model("dummy-model")
            .contents(
                ImmutableList.of(
                    Content.builder().role("user").parts(Part.fromText("prompt text")).build()));
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("response text")).build())
            .build();
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.beforeModelCallback(callbackContext, request).blockingGet();
    plugin.afterModelCallback(callbackContext, response).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    Map<?, ?> requestData = entryData("llm_request");
    assertThat(requestData.get("model")).isEqualTo("dummy-model");
    assertThat(requestData.get("contents"))
        .isEqualTo(ImmutableList.of(textContent("user", "prompt text")));
    assertThat(entryData("llm_response").get("content"))
        .isEqualTo(textContent("model", "response text"));
  }

  @Test
  public void writesInvocationDocumentToFile() throws Exception {
    runEveryHook(plugin());

    Map<String, Object> document = readDocuments().get(0);
    assertThat(document.keySet())
        .containsExactly(
            "invocation_id", "session_id", "app_name", "user_id", "start_time", "entries")
        .inOrder();
    assertThat(document)
        .containsAtLeast(
            "invocation_id", INVOCATION_ID,
            "session_id", "session_id",
            "app_name", APP_NAME,
            "user_id", USER_ID);
    assertThat(entryTypes().get(1)).isEqualTo("invocation_start");
    assertThat(entry("invocation_start").get("agent_name")).isEqualTo(AGENT_NAME);
    assertThat(Iterables.getLast(entryTypes())).isEqualTo("invocation_end");
    assertThat(Files.readString(tracePath)).startsWith("---");
  }

  @Test
  public void sessionStateSnapshot_carriesTheStateAndEventCount() throws Exception {
    Session withState =
        Session.builder("session_id")
            .appName(APP_NAME)
            .userId(USER_ID)
            .state(ImmutableMap.of("cart", "socks"))
            .events(ImmutableList.of(event, event.toBuilder().id("event_2").build()))
            .build();
    useSession(withState);

    runEveryHook(plugin());

    assertThat(entryData("session_state_snapshot"))
        .containsExactly("state", ImmutableMap.of("cart", "socks"), "event_count", 2);
  }

  @Test
  public void runAndAgentStart_recordTheBranch() throws Exception {
    invocationContext = invocationContext.toBuilder().branch("root.child").build();
    callbackContext = new CallbackContext(invocationContext, /* eventActions= */ null);
    DebugLoggingPlugin plugin = plugin();

    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.beforeAgentCallback(agent, callbackContext).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryData("invocation_start")).containsExactly("branch", "root.child");
    assertThat(entryData("agent_start")).containsExactly("branch", "root.child");
  }

  @Test
  public void entriesWithoutData_omitTheDataKey() throws Exception {
    runEveryHook(plugin());

    assertThat(entry("invocation_start")).doesNotContainKey("data");
    assertThat(entry("agent_start")).doesNotContainKey("data");
    assertThat(entry("agent_end")).doesNotContainKey("data");
    assertThat(entry("invocation_end")).doesNotContainKey("data");
  }

  @Test
  public void anonymousErrorClass_isRecordedAsUnknown() throws Exception {
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin
        .onModelErrorCallback(callbackContext, llmRequest, new RuntimeException() {})
        .blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();

    assertThat(entryData("llm_error").get("error_type")).isEqualTo("Unknown");
  }

  @Test
  public void requestedToolConfirmations_areRecordedAsACount() throws Exception {
    ToolConfirmation confirmation = ToolConfirmation.builder().hint("Ship it?").build();
    runEvent(
        eventWithActions(
            EventActions.builder()
                .requestedToolConfirmations(
                    ImmutableMap.of("call-1", confirmation, "call-2", confirmation))
                .build()));

    Map<?, ?> actions = (Map<?, ?>) entryData("event").get("actions");
    assertThat(actions).containsExactly("requested_tool_confirmations", 2);
  }

  private DebugLoggingPlugin plugin() {
    return DebugLoggingPlugin.builder().outputPath(tracePath).build();
  }

  /** Drives every hook once, user message first. */
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

  private ImmutableList<ImmutableMap<String, Object>> readDocuments() throws Exception {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    try (MappingIterator<Map<String, Object>> documents =
        yaml.readerFor(Map.class).readValues(tracePath.toFile())) {
      return documents.readAll().stream().map(ImmutableMap::copyOf).collect(toImmutableList());
    }
  }

  private ImmutableList<String> entryTypes() throws Exception {
    List<?> entries = (List<?>) readDocuments().get(0).get("entries");
    return entries.stream()
        .map(entry -> (String) ((Map<?, ?>) entry).get("entry_type"))
        .collect(toImmutableList());
  }

  private static ImmutableList<String> invocationIds(int count) {
    return IntStream.range(0, count)
        .mapToObj(INVOCATION_ID_FORMAT::formatted)
        .collect(toImmutableList());
  }

  /** Runs invocation {@code i} through {@code plugins.get(i)}, all released at once. */
  private void runConcurrently(
      ImmutableList<DebugLoggingPlugin> plugins, ImmutableList<String> invocationIds)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(plugins.size());
    ExecutorService pool = Executors.newFixedThreadPool(plugins.size());
    try {
      for (int i = 0; i < plugins.size(); i++) {
        DebugLoggingPlugin plugin = plugins.get(i);
        InvocationContext context = invocation(invocationIds.get(i));
        pool.execute(() -> awaitThenRun(plugin, context, start, done));
      }
      start.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  /** Points every context at a real invocation of {@code invocationSession}. */
  private void useSession(Session invocationSession) {
    invocationContext = invocation(INVOCATION_ID, invocationSession);
    callbackContext = new CallbackContext(invocationContext, /* eventActions= */ null);
    toolContext = ToolContext.builder(invocationContext).functionCallId("call-1").build();
  }

  private InvocationContext invocation(String invocationId) {
    return invocation(invocationId, session);
  }

  private InvocationContext invocation(String invocationId, Session invocationSession) {
    return createInvocationContext(agent, new InMemorySessionService(), invocationSession)
        .toBuilder()
        .invocationId(invocationId)
        .instantSource(InstantSource.fixed(NOW))
        .build();
  }

  private static void awaitThenRun(
      DebugLoggingPlugin plugin,
      InvocationContext context,
      CountDownLatch start,
      CountDownLatch done) {
    try {
      start.await();
      plugin.beforeRunCallback(context).blockingGet();
      plugin.afterRunCallback(context).blockingAwait();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      done.countDown();
    }
  }

  public static Map<String, Object> alpha() {
    return ImmutableMap.of();
  }

  public static Map<String, Object> beta() {
    return ImmutableMap.of();
  }

  public static Map<String, Object> gamma() {
    return ImmutableMap.of();
  }

  /** One turn through a real {@link Runner} with the plugin registered. */
  private Flowable<Event> runAsync(LlmAgent rootAgent) {
    Runner runner = runner(rootAgent);
    Session runSession = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
    return runner.runAsync(USER_ID, runSession.id(), userMessage);
  }

  private Runner runner(LlmAgent rootAgent) {
    return Runner.builder()
        .app(
            App.builder()
                .name(APP_NAME)
                .rootAgent(rootAgent)
                .plugins(ImmutableList.of(plugin()))
                .build())
        .build();
  }

  /** A tool that is never run; the hooks only read its name. */
  private static final class LookupOrderTool extends BaseTool {
    LookupOrderTool() {
      super("lookup_order", "Looks up an order");
    }
  }

  private static final class Printable {
    @Override
    public String toString() {
      return "printable";
    }
  }

  private static final class Unprintable {
    @Override
    public String toString() {
      throw new IllegalStateException("no text");
    }
  }

  private void runToolResponse(Map<String, Object> result) {
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.afterToolCallback(tool, toolArgs, toolContext, result).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();
  }

  private void runEvent(Event runEvent) {
    DebugLoggingPlugin plugin = plugin();
    plugin.beforeRunCallback(invocationContext).blockingGet();
    plugin.onEventCallback(invocationContext, runEvent).blockingGet();
    plugin.afterRunCallback(invocationContext).blockingAwait();
  }

  private Event eventWithActions(EventActions actions) {
    return event.toBuilder().actions(actions).build();
  }

  private static ImmutableMap<String, Object> textContent(String role, String text) {
    return ImmutableMap.of("role", role, "parts", ImmutableList.of(ImmutableMap.of("text", text)));
  }

  /** The data of the first entry of that type. */
  private ImmutableMap<?, ?> entryData(String entryType) throws Exception {
    return ImmutableMap.copyOf((Map<?, ?>) entry(entryType).get("data"));
  }

  /**
   * The first entry of that type, found by type rather than index so that a new entry cannot
   * repoint an assertion.
   */
  private ImmutableMap<?, ?> entry(String entryType) throws Exception {
    List<?> entries = (List<?>) readDocuments().get(0).get("entries");
    return entries.stream()
        .map(entry -> (Map<?, ?>) entry)
        .filter(entry -> entryType.equals(entry.get("entry_type")))
        .findFirst()
        .map(ImmutableMap::copyOf)
        .orElseThrow();
  }
}
