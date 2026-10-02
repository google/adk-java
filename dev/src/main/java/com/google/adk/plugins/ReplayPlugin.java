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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

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
import com.google.common.annotations.VisibleForTesting;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin for replaying ADK agent interactions from recordings.
 *
 * <p>The replay case directory comes from the session state, which any caller of the dev server can
 * set. Recordings are therefore only loaded from inside a replay root directory that the server
 * operator configures: the {@code adk.replay.root} system property, the {@code ADK_REPLAY_ROOT}
 * environment variable, or the process working directory when neither is set. Case directories that
 * resolve outside the root, through {@code ..} segments, an absolute path or a symlink, are
 * rejected. A relative case directory resolves against the root, not against the working directory.
 *
 * <p>The working-directory fallback rarely matches where the recordings live, so configure the root
 * explicitly; the plugin logs the root it ended up with when it is constructed.
 */
public class ReplayPlugin extends BasePlugin {
  private static final Logger logger = LoggerFactory.getLogger(ReplayPlugin.class);
  private static final String REPLAY_CONFIG_KEY = "_adk_replay_config";
  private static final String RECORDINGS_FILENAME = "generated-recordings.yaml";
  private static final String REPLAY_ROOT_PROPERTY = "adk.replay.root";
  private static final String REPLAY_ROOT_ENV = "ADK_REPLAY_ROOT";

  // Track replay state per invocation to support concurrent runs
  // key: invocation_id -> InvocationReplayState
  private final Map<String, InvocationReplayState> invocationStates;

  // Recordings are only read from inside this directory, never from arbitrary session state paths.
  private final Path replayRoot;

  public ReplayPlugin() {
    this("adk_replay");
  }

  public ReplayPlugin(String name) {
    this(name, defaultReplayRoot(), configuredReplayRoot() == null);
  }

  /** Creates a plugin that only loads recordings from inside {@code replayRoot}. */
  @VisibleForTesting
  ReplayPlugin(String name, Path replayRoot) {
    this(name, replayRoot, /* usingWorkingDirectory= */ false);
  }

  private ReplayPlugin(String name, Path replayRoot, boolean usingWorkingDirectory) {
    super(name);
    this.replayRoot = checkNotNull(replayRoot).toAbsolutePath().normalize();
    this.invocationStates = new ConcurrentHashMap<>();
    logReplayRoot(usingWorkingDirectory);
  }

  private static Path defaultReplayRoot() {
    String configured = configuredReplayRoot();
    return Paths.get(configured != null ? configured : System.getProperty("user.dir", ""));
  }

  private static @Nullable String configuredReplayRoot() {
    return configuredReplayRoot(
        System.getProperty(REPLAY_ROOT_PROPERTY), System.getenv(REPLAY_ROOT_ENV));
  }

  /** Returns the operator-configured replay root, or null when neither knob carries a value. */
  @VisibleForTesting
  static @Nullable String configuredReplayRoot(
      @Nullable String propertyValue, @Nullable String environmentValue) {
    String configured = isNullOrEmpty(propertyValue) ? environmentValue : propertyValue;
    return isNullOrEmpty(configured) ? null : configured;
  }

  /** Reports the effective replay root once, so a misconfigured root shows up at startup. */
  private void logReplayRoot(boolean usingWorkingDirectory) {
    String readable = Files.isDirectory(replayRoot) ? "" : " (not a readable directory)";
    if (usingWorkingDirectory) {
      logger.warn(
          "Replay recordings are confined to the working directory {}{}, which is rarely where"
              + " recordings live. Set -D{} or {} to the directory holding them.",
          replayRoot,
          readable,
          REPLAY_ROOT_PROPERTY,
          REPLAY_ROOT_ENV);
    } else {
      logger.info("Replay recordings are confined to {}{}", replayRoot, readable);
    }
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
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
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
    return recording
        .llmResponses()
        .filter(responses -> !responses.isEmpty())
        .map(responses -> Maybe.just(responses.get(0)))
        .orElse(Maybe.empty());
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
        .orElseGet(() -> Maybe.empty());
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
    Map<String, Object> config = replayConfig(sessionState);
    return config != null && config.get("dir") != null && config.get("user_message_index") != null;
  }

  /** Returns the replay config from session state, or null when replay is not configured. */
  private static Map<String, Object> replayConfig(Map<String, Object> sessionState) {
    Object config = sessionState.get(REPLAY_CONFIG_KEY);
    if (!(config instanceof Map)) {
      return null;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> typedConfig = (Map<String, Object>) config;
    return typedConfig;
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

    Map<String, Object> config = replayConfig(sessionState);
    if (config == null) {
      throw new ReplayConfigError("Replay parameters are missing from session state");
    }

    Object caseDirValue = config.get("dir");
    Object msgIndexValue = config.get("user_message_index");
    if (caseDirValue == null || msgIndexValue == null) {
      throw new ReplayConfigError("Replay parameters are missing from session state");
    }
    if (!(caseDirValue instanceof String)) {
      throw new ReplayConfigError(
          "Replay parameter 'dir' must be a string, got "
              + caseDirValue.getClass().getSimpleName());
    }
    String caseDir = (String) caseDirValue;
    int msgIndex = userMessageIndex(msgIndexValue);

    // Load recordings
    Path recordingsFile = resolveRecordingsFile(caseDir);

    // NOFOLLOW_LINKS: the path was canonical when it was checked, so refuse it if it became a
    // symlink in between.
    try (InputStream recordingsStream =
        Files.newInputStream(recordingsFile, LinkOption.NOFOLLOW_LINKS)) {
      Recordings recordings = RecordingsLoader.load(recordingsStream);

      // Create and store invocation state
      InvocationReplayState state = new InvocationReplayState(caseDir, msgIndex, recordings);
      invocationStates.put(invocationId, state);

      // The case directory is caller input, so log the counts rather than the path.
      logger.debug(
          "Loaded replay state for invocation {}: msg_index={}, recordings={}",
          invocationId,
          msgIndex,
          recordings.recordings().size());

    } catch (IOException e) {
      // The parser quotes the offending line, so neither the cause nor the file name is safe to
      // carry: the name embeds the caller's case directory. Report the failure shape instead.
      throw new ReplayConfigError(
          "Failed to load the recordings file under "
              + replayRoot
              + " ("
              + e.getClass().getSimpleName()
              + ")");
    }
  }

  /**
   * Returns the user message index, which YAML and JSON decoders hand over as any numeric type.
   *
   * <p>Anything that is not a whole number in range is rejected rather than narrowed, so a bad
   * index cannot quietly select the wrong recording.
   */
  private static int userMessageIndex(Object value) {
    if (!(value instanceof Number)) {
      throw new ReplayConfigError(
          "Replay parameter 'user_message_index' must be a number, got "
              + value.getClass().getSimpleName());
    }
    double asDouble = ((Number) value).doubleValue();
    if (asDouble != Math.floor(asDouble) || asDouble < 0 || asDouble > Integer.MAX_VALUE) {
      throw new ReplayConfigError(
          "Replay parameter 'user_message_index' must be a whole number in [0, "
              + Integer.MAX_VALUE
              + "]");
    }
    return ((Number) value).intValue();
  }

  /**
   * Resolves the recordings file for a session-supplied case directory, keeping it inside the
   * replay root.
   *
   * <p>Containment is decided on the canonical path, so neither {@code ..} nor a symlink can leave
   * the root however the caller spells it. That canonical path is what is returned, so the caller
   * opens exactly what was checked. Messages never echo the case directory, which is caller input.
   */
  private Path resolveRecordingsFile(String caseDir) {
    Path canonicalRoot;
    try {
      canonicalRoot = replayRoot.toRealPath();
    } catch (IOException e) {
      throw new ReplayConfigError(
          "Replay root directory is not readable: "
              + replayRoot
              + ". Set -D"
              + REPLAY_ROOT_PROPERTY
              + " to the directory holding the recordings.",
          e);
    }

    Path recordingsFile;
    try {
      recordingsFile = replayRoot.resolve(caseDir).normalize().resolve(RECORDINGS_FILENAME);
    } catch (InvalidPathException e) {
      throw new ReplayConfigError("Replay parameter 'dir' is not a valid path", e);
    }

    Path canonicalFile;
    try {
      canonicalFile = recordingsFile.toRealPath();
    } catch (NoSuchFileException e) {
      throw new ReplayConfigError("Recordings file not found under the replay root " + replayRoot);
    } catch (IOException e) {
      throw new ReplayConfigError("Recordings file is not readable under " + replayRoot, e);
    }
    if (!canonicalFile.startsWith(canonicalRoot)) {
      throw new ReplayConfigError(
          "Replay directory resolves outside the replay root " + replayRoot);
    }

    return canonicalFile;
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
      InvocationReplayState state, String agentName, LlmRequest.Builder llmRequest) {
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
          llmRecording.llmRequest().get(), llmRequest.build(), agentName, currentAgentIndex);
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
    String diff = comparator.diff(recordedRequest, currentRequest);
    if (!diff.isEmpty()) {
      logger.error(
          String.format(
              "LLM request mismatch for agent '%s' (index %d):%n%s", agentName, agentIndex, diff));
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
      logger.error(
          String.format(
              "Tool name mismatch for agent '%s' at index %d:%nrecorded: '%s'%ncurrent: '%s'",
              agentName, agentIndex, recordedName, toolName));
    }

    // Verify tool arguments
    Map<String, Object> recordedArgs = recordedCall.args().orElse(Map.of());
    if (!recordedArgs.equals(toolArgs)) {
      logger.error(
          String.format(
              "Tool args mismatch for agent '%s' at index %d:%nrecorded: %s%ncurrent: %s",
              agentName, agentIndex, recordedArgs, toolArgs));
    }
  }
}
