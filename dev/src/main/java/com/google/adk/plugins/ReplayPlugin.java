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

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.recordings.LlmRecording;
import com.google.adk.plugins.recordings.Recording;
import com.google.adk.plugins.recordings.Recordings;
import com.google.adk.plugins.recordings.RecordingsLoader;
import com.google.adk.plugins.recordings.ToolRecording;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plugin for replaying ADK agent interactions from recordings. */
public class ReplayPlugin extends BasePlugin {
  private static final Logger logger = LoggerFactory.getLogger(ReplayPlugin.class);
  private static final String REPLAY_CONFIG_KEY = "_adk_replay_config";
  private static final String RECORDINGS_FILENAME = "generated-recordings.yaml";

  // Track replay state per invocation to support concurrent runs
  // key: invocation_id -> InvocationReplayState
  private final Map<String, InvocationReplayState> invocationStates;

  public ReplayPlugin() {
    this("adk_replay");
  }

  public ReplayPlugin(String name) {
    super(name);
    this.invocationStates = new ConcurrentHashMap<>();
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    if (isReplayModeOn(invocationContext)) {
      loadInvocationState(invocationContext);
    }
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest llmRequest) {
    if (!isReplayModeOn(callbackContext)) {
      return Maybe.empty();
    }

    InvocationReplayState state = getInvocationState(callbackContext);
    if (state == null) {
      throw new ReplayConfigError(
          "Replay state not initialized. Ensure beforeRunCallback created it.");
    }

    String agentName = callbackContext.agentName();

    // Verify and get the next LLM recording for this specific agent
    LlmRecording recording = verifyAndGetNextLlmRecordingForAgent(state, agentName, llmRequest);

    logger.debug("Verified and replaying LLM response for agent {}", agentName);

    // Return the recorded response
    return recording.llmResponse().map(Maybe::just).orElse(Maybe.empty());
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    if (!isReplayModeOn(toolContext)) {
      return Maybe.empty();
    }

    InvocationReplayState state = getInvocationState(toolContext);
    if (state == null) {
      throw new ReplayConfigError(
          "Replay state not initialized. Ensure beforeRunCallback created it.");
    }

    String agentName = toolContext.agentName();

    // Verify and get the next tool recording for this specific agent
    ToolRecording recording =
        verifyAndGetNextToolRecordingForAgent(state, agentName, tool.name(), toolArgs);

    if (!(tool instanceof AgentTool)) {
      // TODO: support replay requests and responses from AgentTool.
      // For now, execute the tool normally to maintain side effects
      try {
        Map<String, Object> liveResult = tool.runAsync(toolArgs, toolContext).blockingGet();
        logger.debug("Tool {} executed during replay with result: {}", tool.name(), liveResult);
      } catch (Exception e) {
        logger.warn("Error executing tool {} during replay", tool.name(), e);
      }
    }

    logger.debug(
        "Verified and replaying tool response for agent {}: tool={}", agentName, tool.name());

    // Return the recorded response
    return recording
        .toolResponse()
        .flatMap(fr -> fr.response().map(resp -> (Map<String, Object>) resp))
        .map(Maybe::just)
        .orElse(Maybe.empty());
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    if (!isReplayModeOn(invocationContext)) {
      return Completable.complete();
    }

    // Clean up per-invocation replay state
    invocationStates.remove(invocationContext.invocationId());
    logger.debug("Cleaned up replay state for invocation {}", invocationContext.invocationId());

    return Completable.complete();
  }

  // Private helpers

  private boolean isReplayModeOn(InvocationContext invocationContext) {
    Map<String, Object> sessionState = invocationContext.session().state();
    return isReplayModeOnFromState(sessionState);
  }

  private boolean isReplayModeOn(CallbackContext callbackContext) {
    Map<String, Object> sessionState = callbackContext.state();
    return isReplayModeOnFromState(sessionState);
  }

  private boolean isReplayModeOn(ToolContext toolContext) {
    Map<String, Object> sessionState = toolContext.state();
    return isReplayModeOnFromState(sessionState);
  }

  private boolean isReplayModeOnFromState(Map<String, Object> sessionState) {
    if (!sessionState.containsKey(REPLAY_CONFIG_KEY)) {
      return false;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) sessionState.get(REPLAY_CONFIG_KEY);

    String caseDir = (String) config.get("dir");
    Integer msgIndex = (Integer) config.get("user_message_index");

    return caseDir != null && msgIndex != null;
  }

  private InvocationReplayState getInvocationState(CallbackContext callbackContext) {
    return invocationStates.get(callbackContext.invocationId());
  }

  private InvocationReplayState getInvocationState(ToolContext toolContext) {
    return invocationStates.get(toolContext.invocationId());
  }

  private void loadInvocationState(InvocationContext invocationContext) {
    String invocationId = invocationContext.invocationId();
    Map<String, Object> sessionState = invocationContext.session().state();

    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) sessionState.get(REPLAY_CONFIG_KEY);
    if (config == null) {
      throw new ReplayConfigError("Replay parameters are missing from session state");
    }

    String caseDir = (String) config.get("dir");
    Integer msgIndex = (Integer) config.get("user_message_index");

    if (caseDir == null || msgIndex == null) {
      throw new ReplayConfigError("Replay parameters are missing from session state");
    }

    // Load recordings
    Path recordingsFile = Paths.get(caseDir, RECORDINGS_FILENAME);

    if (!Files.exists(recordingsFile)) {
      throw new ReplayConfigError("Recordings file not found: " + recordingsFile);
    }

    try {
      Recordings recordings = RecordingsLoader.load(recordingsFile);

      // Create and store invocation state
      InvocationReplayState state = new InvocationReplayState(caseDir, msgIndex, recordings);
      invocationStates.put(invocationId, state);

      logger.debug(
          "Loaded replay state for invocation {}: case_dir={}, msg_index={}, recordings={}",
          invocationId,
          caseDir,
          msgIndex,
          recordings.recordings().size());

    } catch (IOException e) {
      throw new ReplayConfigError(
          "Failed to load recordings from " + recordingsFile + ": " + e.getMessage(), e);
    }
  }

  private Recording getNextRecordingForAgent(InvocationReplayState state, String agentName) {
    int currentAgentIndex = state.getAgentReplayIndex(agentName);

    // Filter ALL recordings for this agent and user message index (strict order)
    List<Recording> agentRecordings = new ArrayList<>();
    for (Recording recording : state.getRecordings().recordings()) {
      if (recording.agentName().equals(agentName)
          && recording.userMessageIndex() == state.getUserMessageIndex()) {
        agentRecordings.add(recording);
      }
    }

    // Check if we have enough recordings for this agent
    if (currentAgentIndex >= agentRecordings.size()) {
      throw new ReplayVerificationError(
          String.format(
              "Runtime sent more requests than expected for agent '%s' at user_message_index %d. "
                  + "Expected %d, but got request at index %d",
              agentName, state.getUserMessageIndex(), agentRecordings.size(), currentAgentIndex));
    }

    // Get the expected recording
    Recording expectedRecording = agentRecordings.get(currentAgentIndex);

    // Advance agent index
    state.incrementAgentReplayIndex(agentName);

    return expectedRecording;
  }

  private LlmRecording verifyAndGetNextLlmRecordingForAgent(
      InvocationReplayState state, String agentName, LlmRequest llmRequest) {
    int currentAgentIndex = state.getAgentReplayIndex(agentName);
    Recording expectedRecording = getNextRecordingForAgent(state, agentName);

    // Verify this is an LLM recording
    if (!expectedRecording.llmRecording().isPresent()) {
      throw new ReplayVerificationError(
          String.format(
              "Expected LLM recording for agent '%s' at index %d, but found tool recording",
              agentName, currentAgentIndex));
    }

    LlmRecording llmRecording = expectedRecording.llmRecording().get();

    // Strict verification of LLM request
    if (llmRecording.llmRequest().isPresent()) {
      verifyLlmRequestMatch(
          llmRecording.llmRequest().get(), llmRequest, agentName, currentAgentIndex);
    }

    return llmRecording;
  }

  private ToolRecording verifyAndGetNextToolRecordingForAgent(
      InvocationReplayState state,
      String agentName,
      String toolName,
      Map<String, Object> toolArgs) {
    int currentAgentIndex = state.getAgentReplayIndex(agentName);
    Recording expectedRecording = getNextRecordingForAgent(state, agentName);

    // Verify this is a tool recording
    if (!expectedRecording.toolRecording().isPresent()) {
      throw new ReplayVerificationError(
          String.format(
              "Expected tool recording for agent '%s' at index %d, but found LLM recording",
              agentName, currentAgentIndex));
    }

    ToolRecording toolRecording = expectedRecording.toolRecording().get();

    // Strict verification of tool call
    if (toolRecording.toolCall().isPresent()) {
      verifyToolCallMatch(
          toolRecording.toolCall().get(), toolName, toolArgs, agentName, currentAgentIndex);
    }

    return toolRecording;
  }

  /**
   * Verify that the current LLM request exactly matches the recorded one.
   *
   * <p>Compares requests excluding fields that can vary between runs (like live_connect_config,
   * http_options, and labels).
   */
  private void verifyLlmRequestMatch(
      LlmRequest recordedRequest, LlmRequest currentRequest, String agentName, int agentIndex) {
    LlmRequestComparator comparator = new LlmRequestComparator();
    if (!comparator.equals(recordedRequest, currentRequest)) {
      throw new ReplayVerificationError(
          String.format(
              "LLM request mismatch for agent '%s' (index %d):%nrecorded: %s%ncurrent: %s",
              agentName,
              agentIndex,
              comparator.toComparisonMap(recordedRequest),
              comparator.toComparisonMap(currentRequest)));
    }
  }

  /**
   * Verify that the current tool call exactly matches the recorded one.
   *
   * <p>Compares tool name and arguments for exact match.
   */
  private void verifyToolCallMatch(
      FunctionCall recordedCall,
      String toolName,
      Map<String, Object> toolArgs,
      String agentName,
      int agentIndex) {
    // Verify tool name
    String recordedName = recordedCall.name().orElse("");
    if (!recordedName.equals(toolName)) {
      throw new ReplayVerificationError(
          String.format(
              "Tool name mismatch for agent '%s' at index %d:%nrecorded: '%s'%ncurrent: '%s'",
              agentName, agentIndex, recordedName, toolName));
    }

    // Verify tool arguments
    Map<String, Object> recordedArgs = recordedCall.args().orElse(Map.of());
    if (!recordedArgs.equals(toolArgs)) {
      throw new ReplayVerificationError(
          String.format(
              "Tool args mismatch for agent '%s' at index %d:%nrecorded: %s%ncurrent: %s",
              agentName, agentIndex, recordedArgs, toolArgs));
    }
  }
}
