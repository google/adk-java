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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.debuglogging.DebugEntry.Type;
import com.google.adk.plugins.debuglogging.TracePayload.BranchTrace;
import com.google.adk.plugins.debuglogging.TracePayload.LlmErrorTrace;
import com.google.adk.plugins.debuglogging.TracePayload.MarkerTrace;
import com.google.adk.plugins.debuglogging.TracePayload.SessionStateTrace;
import com.google.adk.plugins.debuglogging.TracePayload.ToolCallTrace;
import com.google.adk.plugins.debuglogging.TracePayload.ToolErrorTrace;
import com.google.adk.plugins.debuglogging.TracePayload.ToolResponseTrace;
import com.google.adk.plugins.debuglogging.TracePayload.UserMessageTrace;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Captures a complete record of an invocation to a YAML file for debugging.
 *
 * <p>Each invocation becomes one YAML document appended to the output file, holding the user
 * message, every LLM request and response, every tool call and result, every event yielded by the
 * runner, and — optionally — the session state as the invocation ended.
 *
 * <p>Port of adk-python's {@code DebugLoggingPlugin}. Refer to:
 * https://github.com/google/adk-python/blob/main/src/google/adk/plugins/debug_logging_plugin.py
 *
 * <p>Example:
 *
 * <pre>{@code
 * Runner runner =
 *     new InMemoryRunner(
 *         agent, APP_NAME, ImmutableList.of(new DebugLoggingPlugin(Path.of("adk_debug.yaml"))));
 * }</pre>
 *
 * <p><b>Every hook is observe-only.</b> None returns a value that changes the run: the plugin
 * cannot rewrite a request, substitute a tool result, or swallow an error. Failures inside it are
 * logged, never thrown.
 *
 * <p>Traces contain whatever the model and the tools said, so treat the output file as sensitive:
 * it is meant to be read, and pasted into a bug report, by someone entitled to see the
 * conversation. Two things are deliberately never written — the bytes of inline data, and the
 * contents of requested auth configs, of which only a count is recorded.
 */
public class DebugLoggingPlugin extends BasePlugin {

  private static final String DEFAULT_NAME = "debug_logging_plugin";
  private static final String DEFAULT_OUTPUT_PATH = "adk_debug.yaml";

  private final boolean includeSessionState;
  private final boolean includeSystemInstruction;
  private final DebugTraceRecorder recorder = new DebugTraceRecorder(Clock.systemDefaultZone());
  private final DebugYamlWriter writer;

  /** Writes {@code adk_debug.yaml} in the working directory, recording everything. */
  public DebugLoggingPlugin() {
    this(DEFAULT_NAME, Path.of(DEFAULT_OUTPUT_PATH), true, true);
  }

  /** As above, at a path of your choosing. */
  public DebugLoggingPlugin(Path outputPath) {
    this(DEFAULT_NAME, outputPath, true, true);
  }

  /**
   * @param name plugin instance identifier
   * @param outputPath file the YAML documents are appended to; missing parent directories are
   *     created
   * @param includeSessionState whether to append a session state snapshot when an invocation ends
   * @param includeSystemInstruction whether to record system instructions in full, rather than
   *     noting only that one was present
   * @throws NullPointerException if {@code outputPath} is null
   */
  public DebugLoggingPlugin(
      String name, Path outputPath, boolean includeSessionState, boolean includeSystemInstruction) {
    super(name);
    this.writer = new DebugYamlWriter(checkNotNull(outputPath, "outputPath cannot be null"));
    this.includeSessionState = includeSessionState;
    this.includeSystemInstruction = includeSystemInstruction;
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    startInvocation(invocationContext);
    return Maybe.empty();
  }

  /**
   * Records the message that started the invocation.
   *
   * <p>This hook runs <em>before</em> {@link #beforeRunCallback}, so it opens the invocation rather
   * than assuming one is already open — see {@link DebugTraceRecorder#start}.
   */
  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    openInvocation(invocationContext);
    recorder.record(
        invocationContext.invocationId(), Type.USER_MESSAGE, UserMessageTrace.from(userMessage));
    return Maybe.empty();
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    recordBranch(callbackContext, Type.AGENT_START);
    return Maybe.empty();
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    recorder.record(
        callbackContext.invocationId(),
        Type.AGENT_END,
        callbackContext.agentName(),
        MarkerTrace.INSTANCE);
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    recorder.record(
        callbackContext.invocationId(),
        Type.LLM_REQUEST,
        callbackContext.agentName(),
        LlmRequestTrace.from(llmRequest.build(), includeSystemInstruction));
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    recorder.record(
        callbackContext.invocationId(),
        Type.LLM_RESPONSE,
        callbackContext.agentName(),
        LlmResponseTrace.from(llmResponse));
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    recorder.record(
        callbackContext.invocationId(),
        Type.LLM_ERROR,
        callbackContext.agentName(),
        LlmErrorTrace.from(error, llmRequest.build()));
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    recorder.record(
        toolContext.invocationId(),
        Type.TOOL_CALL,
        toolContext.agentName(),
        ToolCallTrace.of(tool.name(), toolContext.functionCallId().orElse(null), toolArgs));
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    recorder.record(
        toolContext.invocationId(),
        Type.TOOL_RESPONSE,
        toolContext.agentName(),
        ToolResponseTrace.of(tool.name(), toolContext.functionCallId().orElse(null), result));
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    recorder.record(
        toolContext.invocationId(),
        Type.TOOL_ERROR,
        toolContext.agentName(),
        ToolErrorTrace.of(tool.name(), toolContext.functionCallId().orElse(null), toolArgs, error));
    return Maybe.empty();
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    recorder.record(
        invocationContext.invocationId(), Type.EVENT, event.author(), EventTrace.from(event));
    return Maybe.empty();
  }

  /**
   * Writes the invocation out.
   *
   * <p>{@code afterRunCallback} returns a {@link Completable}, so the file write runs on {@link
   * Schedulers#io()} rather than on whichever thread finished the run.
   */
  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromAction(() -> flush(invocationContext)).subscribeOn(Schedulers.io());
  }

  /** Opens the invocation if this is the first hook to reach it; harmless if it is not. */
  private void openInvocation(InvocationContext context) {
    recorder.start(InvocationDebugState.of(context, recorder.now()));
  }

  private void startInvocation(InvocationContext context) {
    openInvocation(context);
    recorder.record(
        context.invocationId(),
        Type.INVOCATION_START,
        context.agent().name(),
        new BranchTrace(context.branch()));
  }

  private void recordBranch(CallbackContext callbackContext, Type type) {
    recorder.record(
        callbackContext.invocationId(),
        type,
        callbackContext.agentName(),
        new BranchTrace(callbackContext.branch()));
  }

  private void flush(InvocationContext context) {
    recorder.forWrite(context.invocationId()).ifPresent(state -> closeAndWrite(context, state));
  }

  /**
   * The closing entries go in before the write, and the state is dropped afterwards whatever
   * happens — upstream's {@code finally} in {@code after_run_callback}. Without it a failed write
   * would leak one invocation's entries for the lifetime of the plugin.
   */
  private void closeAndWrite(InvocationContext context, InvocationDebugState state) {
    try {
      if (includeSessionState) {
        recorder.record(
            context.invocationId(),
            Type.SESSION_STATE_SNAPSHOT,
            SessionStateTrace.from(context.session()));
      }
      recorder.record(context.invocationId(), Type.INVOCATION_END, MarkerTrace.INSTANCE);
      writer.append(state);
    } finally {
      recorder.finish(context.invocationId());
    }
  }
}
