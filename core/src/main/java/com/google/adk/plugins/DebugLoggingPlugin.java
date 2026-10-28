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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Collections.newSetFromMap;
import static java.util.function.Predicate.not;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
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
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Blob;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin that captures complete debug information to a YAML file.
 *
 * <p>It records LLM requests and responses, tool calls and results, the events yielded by the
 * runner, and, by default, the session state at the end of each invocation. Each invocation is
 * appended to the file as a separate YAML document. Unlike {@link LoggingPlugin}, which prints
 * truncated summaries to the console, this plugin captures the data in full.
 *
 * <p><b>Caution:</b> the file holds raw prompts, tool arguments and session state. Where the file
 * system supports POSIX permissions, the plugin creates it readable and writable by its owner only.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Runner runner =
 *     new InMemoryRunner(
 *         agent,
 *         APP_NAME,
 *         ImmutableList.of(
 *             DebugLoggingPlugin.builder().outputPath(Path.of("adk_debug.yaml")).build()));
 * }</pre>
 */
public final class DebugLoggingPlugin extends BasePlugin {

  private static final Logger logger = LoggerFactory.getLogger(DebugLoggingPlugin.class);

  private static final String DEFAULT_NAME = "debug_logging_plugin";
  private static final String DEFAULT_OUTPUT_PATH = "adk_debug.yaml";
  private static final String NO_STATE_FOR_ENTRY =
      "[{}] No debug state for invocation {}, skipping entry";
  private static final String NO_STATE_FOR_WRITE =
      "[{}] No debug state for invocation {}, skipping write";
  private static final String WROTE_DEBUG_DATA = "[{}] Wrote debug data for invocation {} to {}";
  private static final String FAILED_TO_WRITE = "[{}] Failed to write debug data for invocation {}";
  private static final String BYTES_MARKER = "<bytes: %d bytes>";
  private static final String UNSERIALIZABLE_MARKER = "<unserializable>";
  private static final String CIRCULAR_REFERENCE = "<circular reference>";
  private static final String UNKNOWN_ERROR_TYPE = "Unknown";
  private static final ImmutableSet<OpenOption> APPEND_OPTIONS =
      ImmutableSet.of(CREATE, WRITE, APPEND);
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
  private static final ObjectMapper YAML =
      new ObjectMapper(
          YAMLFactory.builder().enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE).build());

  private final Path outputPath;
  private final boolean includeSessionState;
  private final boolean includeSystemInstruction;
  private final ConcurrentMap<String, InvocationDebugState> invocationStates =
      new ConcurrentHashMap<>();
  private final Object writeLock = new Object();

  /** Creates a plugin that writes {@code adk_debug.yaml} in the working directory. */
  public DebugLoggingPlugin() {
    this(builder());
  }

  private DebugLoggingPlugin(Builder builder) {
    super(builder.name);
    this.outputPath = builder.outputPath;
    this.includeSessionState = builder.includeSessionState;
    this.includeSystemInstruction = builder.includeSystemInstruction;
  }

  /** Returns a builder with the same defaults as {@link #DebugLoggingPlugin()}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link DebugLoggingPlugin}. */
  public static final class Builder {
    private Path outputPath = Path.of(DEFAULT_OUTPUT_PATH);
    private boolean includeSessionState = true;
    private boolean includeSystemInstruction = true;
    private String name = DEFAULT_NAME;

    private Builder() {}

    /** Sets the file the YAML documents are appended to; missing parent directories are created. */
    @CanIgnoreReturnValue
    public Builder outputPath(Path outputPath) {
      this.outputPath = checkNotNull(outputPath);
      return this;
    }

    /** Sets whether a session state snapshot is recorded at the end of each invocation. */
    @CanIgnoreReturnValue
    public Builder includeSessionState(boolean includeSessionState) {
      this.includeSessionState = includeSessionState;
      return this;
    }

    /** Sets whether system instructions are recorded in full, rather than only their presence. */
    @CanIgnoreReturnValue
    public Builder includeSystemInstruction(boolean includeSystemInstruction) {
      this.includeSystemInstruction = includeSystemInstruction;
      return this;
    }

    /** Sets the plugin instance name. */
    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = checkNotNull(name);
      return this;
    }

    public DebugLoggingPlugin build() {
      return new DebugLoggingPlugin(this);
    }
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    // The runner invokes this hook before beforeRunCallback, so the state may not exist yet.
    ensureState(invocationContext);
    addEntry(
        invocationContext,
        "user_message",
        null,
        dataOf(field("content", serializeContent(userMessage))));
    return Maybe.empty();
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    ensureState(invocationContext);
    addEntry(
        invocationContext,
        "invocation_start",
        invocationContext.agent().name(),
        dataOf(field("branch", invocationContext.branch())));
    return Maybe.empty();
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    addEntry(invocationContext, "event", event.author(), serializeEvent(event));
    return Maybe.empty();
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromAction(() -> flush(invocationContext));
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    addEntry(
        callbackContext.invocationContext(),
        "agent_start",
        callbackContext.agentName(),
        dataOf(field("branch", callbackContext.branch())));
    return Maybe.empty();
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    addEntry(
        callbackContext.invocationContext(),
        "agent_end",
        callbackContext.agentName(),
        ImmutableMap.of());
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    addEntry(
        callbackContext.invocationContext(),
        "llm_request",
        callbackContext.agentName(),
        serializeRequest(llmRequest.build()));
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    addEntry(
        callbackContext.invocationContext(),
        "llm_response",
        callbackContext.agentName(),
        serializeResponse(llmResponse));
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    addEntry(
        callbackContext.invocationContext(),
        "llm_error",
        callbackContext.agentName(),
        dataOf(
            field("error_type", errorType(error)),
            field("error_message", error.getMessage()),
            field("model", llmRequest.build().model())));
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    addEntry(
        toolContext.invocationContext(),
        "tool_call",
        toolContext.agentName(),
        dataOf(
            field("tool_name", tool.name()),
            field("function_call_id", toolContext.functionCallId()),
            field("args", safeSerializeMap(toolArgs))));
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    addEntry(
        toolContext.invocationContext(),
        "tool_response",
        toolContext.agentName(),
        dataOf(
            field("tool_name", tool.name()),
            field("function_call_id", toolContext.functionCallId()),
            field("result", safeSerializeMap(result))));
    return Maybe.empty();
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    addEntry(
        toolContext.invocationContext(),
        "tool_error",
        toolContext.agentName(),
        dataOf(
            field("tool_name", tool.name()),
            field("function_call_id", toolContext.functionCallId()),
            field("args", safeSerializeMap(toolArgs)),
            field("error_type", errorType(error)),
            field("error_message", error.getMessage())));
    return Maybe.empty();
  }

  /** Records the closing entries and appends the invocation; its state is dropped either way. */
  private void flush(InvocationContext context) {
    String invocationId = context.invocationId();
    InvocationDebugState state = invocationStates.get(invocationId);
    if (state == null) {
      logger.warn(NO_STATE_FOR_WRITE, getName(), invocationId);
      return;
    }
    try {
      if (includeSessionState) {
        addEntry(context, "session_state_snapshot", null, serializeSessionState(context.session()));
      }
      addEntry(context, "invocation_end", null, ImmutableMap.of());
      write(state);
    } finally {
      invocationStates.remove(invocationId);
    }
  }

  private void write(InvocationDebugState state) {
    synchronized (writeLock) {
      try {
        append(YAML.writeValueAsString(serializeState(state)));
        logger.debug(WROTE_DEBUG_DATA, getName(), state.invocationId(), outputPath);
      } catch (IOException | RuntimeException e) {
        logger.error(FAILED_TO_WRITE, getName(), state.invocationId(), e);
      }
    }
  }

  private void append(String document) throws IOException {
    Path parent = outputPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (OutputStream out = Channels.newOutputStream(openForAppend())) {
      out.write(document.getBytes(UTF_8));
    }
  }

  /**
   * Opens the file for appending; a file this creates is owner-only where the file system supports
   * POSIX permissions.
   */
  private SeekableByteChannel openForAppend() throws IOException {
    return outputPath.getFileSystem().supportedFileAttributeViews().contains("posix")
        ? Files.newByteChannel(outputPath, APPEND_OPTIONS, OWNER_ONLY)
        : Files.newByteChannel(outputPath, APPEND_OPTIONS);
  }

  private static String errorType(Throwable error) {
    String simpleName = error.getClass().getSimpleName();
    return simpleName.isEmpty() ? UNKNOWN_ERROR_TYPE : simpleName;
  }

  private void ensureState(InvocationContext context) {
    invocationStates.computeIfAbsent(context.invocationId(), unused -> newState(context));
  }

  private static InvocationDebugState newState(InvocationContext context) {
    Session session = context.session();
    return new InvocationDebugState(
        context.invocationId(),
        session.id(),
        session.appName(),
        session.userId(),
        timestamp(context),
        new ConcurrentLinkedQueue<>());
  }

  private void addEntry(
      InvocationContext context,
      String entryType,
      @Nullable String agentName,
      ImmutableMap<String, Object> data) {
    String invocationId = context.invocationId();
    InvocationDebugState state = invocationStates.get(invocationId);
    if (state == null) {
      logger.warn(NO_STATE_FOR_ENTRY, getName(), invocationId);
      return;
    }
    state
        .entries()
        .add(new DebugEntry(timestamp(context), entryType, invocationId, agentName, data));
  }

  private static String timestamp(InvocationContext context) {
    return context.now().toString();
  }

  private static ImmutableMap<String, Object> serializeState(InvocationDebugState state) {
    return dataOf(
        field("invocation_id", state.invocationId()),
        field("session_id", state.sessionId()),
        field("app_name", state.appName()),
        field("user_id", state.userId()),
        field("start_time", state.startTime()),
        field("entries", serializeEntries(state.entries())));
  }

  private static ImmutableList<ImmutableMap<String, Object>> serializeEntries(
      Collection<DebugEntry> entries) {
    return entries.stream().map(DebugLoggingPlugin::serializeEntry).collect(toImmutableList());
  }

  private static ImmutableMap<String, Object> serializeEntry(DebugEntry entry) {
    return dataOf(
        field("timestamp", entry.timestamp()),
        field("entry_type", entry.entryType()),
        field("invocation_id", entry.invocationId()),
        field("agent_name", entry.agentName()),
        field("data", nonEmpty(entry.data())));
  }

  private static ImmutableMap<String, Object> serializeSessionState(Session session) {
    return dataOf(
        field("state", safeSerializeMap(session.state())),
        field("event_count", session.immutableEvents().size()));
  }

  private static ImmutableMap<String, Object> serializeEvent(Event event) {
    return dataOf(
        field("event_id", event.id()),
        field("author", event.author()),
        field("content", event.content().map(DebugLoggingPlugin::serializeContent)),
        field("is_final_response", event.finalResponse()),
        field("partial", event.partial()),
        field("turn_complete", event.turnComplete()),
        field("branch", event.branch()),
        field("actions", serializeActions(event.actions())),
        field("usage_metadata", event.usageMetadata().map(DebugLoggingPlugin::serializeEventUsage)),
        field("has_grounding_metadata", event.groundingMetadata().map(unused -> true)),
        field("error_code", event.errorCode().map(String::valueOf)),
        field("error_message", event.errorMessage()),
        field(
            "long_running_tool_ids",
            event.longRunningToolIds().filter(not(Set::isEmpty)).map(ImmutableList::copyOf)));
  }

  private static Optional<ImmutableMap<String, Object>> serializeActions(EventActions actions) {
    return nonEmpty(
        dataOf(
            field(
                "state_delta",
                nonEmpty(actions.stateDelta()).map(DebugLoggingPlugin::safeSerializeMap)),
            field("artifact_delta", nonEmpty(actions.artifactDelta()).map(ImmutableMap::copyOf)),
            field("transfer_to_agent", actions.transferToAgent()),
            field("escalate", actions.escalate().filter(Boolean::booleanValue)),
            field(
                "requested_auth_configs", nonEmpty(actions.requestedAuthConfigs()).map(Map::size)),
            field(
                "requested_tool_confirmations",
                nonEmpty(actions.requestedToolConfirmations()).map(Map::size))));
  }

  private ImmutableMap<String, Object> serializeRequest(LlmRequest request) {
    return dataOf(
        field("model", request.model()),
        field("content_count", request.contents().size()),
        field("contents", serializeContents(request.contents())),
        field(
            "tools",
            request.config().map(DebugLoggingPlugin::toolNames).filter(not(List::isEmpty))),
        field("config", request.config().flatMap(this::serializeConfig)));
  }

  private static ImmutableList<String> toolNames(GenerateContentConfig config) {
    return config.tools().orElse(ImmutableList.of()).stream()
        .flatMap(tool -> tool.functionDeclarations().orElse(ImmutableList.of()).stream())
        .flatMap(declaration -> declaration.name().stream())
        .collect(toImmutableList());
  }

  private Optional<ImmutableMap<String, Object>> serializeConfig(GenerateContentConfig config) {
    return nonEmpty(
        dataOf(
            systemInstruction(config),
            field("temperature", config.temperature()),
            field("top_p", config.topP()),
            field("top_k", config.topK()),
            field("max_output_tokens", config.maxOutputTokens()),
            field("response_mime_type", config.responseMimeType()),
            field("has_response_schema", config.responseSchema().map(unused -> true))));
  }

  /** The system instruction, or only a note that one is set when the switch is off. */
  private Map.Entry<String, ?> systemInstruction(GenerateContentConfig config) {
    Optional<Content> instruction = config.systemInstruction();
    return includeSystemInstruction
        ? field("system_instruction", instruction.map(DebugLoggingPlugin::serializeContent))
        : field("has_system_instruction", instruction.map(unused -> true));
  }

  private static ImmutableMap<String, Object> serializeResponse(LlmResponse response) {
    return dataOf(
        field("content", response.content().map(DebugLoggingPlugin::serializeContent)),
        field("partial", response.partial()),
        field("turn_complete", response.turnComplete()),
        field("error_code", response.errorCode().map(String::valueOf)),
        field("error_message", response.errorMessage()),
        field(
            "usage_metadata",
            response.usageMetadata().map(DebugLoggingPlugin::serializeResponseUsage)),
        field("has_grounding_metadata", response.groundingMetadata().map(unused -> true)),
        field("finish_reason", response.finishReason().map(String::valueOf)),
        field("model_version", response.modelVersion()));
  }

  private static ImmutableMap<String, Object> serializeEventUsage(
      GenerateContentResponseUsageMetadata usage) {
    return dataOf(
        field("prompt_token_count", usage.promptTokenCount()),
        field("candidates_token_count", usage.candidatesTokenCount()),
        field("total_token_count", usage.totalTokenCount()));
  }

  private static ImmutableMap<String, Object> serializeResponseUsage(
      GenerateContentResponseUsageMetadata usage) {
    return dataOf(
        field("prompt_token_count", usage.promptTokenCount()),
        field("candidates_token_count", usage.candidatesTokenCount()),
        field("total_token_count", usage.totalTokenCount()),
        field("cached_content_token_count", usage.cachedContentTokenCount()));
  }

  private static ImmutableList<ImmutableMap<String, Object>> serializeContents(
      List<Content> contents) {
    return contents.stream().map(DebugLoggingPlugin::serializeContent).collect(toImmutableList());
  }

  private static ImmutableMap<String, Object> serializeContent(Content content) {
    return dataOf(
        field("role", content.role()),
        field(
            "parts",
            content.parts().orElse(ImmutableList.of()).stream()
                .map(DebugLoggingPlugin::serializePart)
                .filter(not(Map::isEmpty))
                .collect(toImmutableList())));
  }

  private static ImmutableMap<String, Object> serializePart(Part part) {
    return dataOf(
        field("text", part.text().filter(not(String::isEmpty))),
        field("function_call", part.functionCall().map(DebugLoggingPlugin::serializeCall)),
        field(
            "function_response",
            part.functionResponse().map(DebugLoggingPlugin::serializeFunctionResponse)),
        field("inline_data", part.inlineData().map(DebugLoggingPlugin::serializeInlineData)),
        field("file_data", part.fileData().map(DebugLoggingPlugin::serializeFileData)),
        field(
            "code_execution_result",
            part.codeExecutionResult().map(DebugLoggingPlugin::serializeCodeExecutionResult)),
        field(
            "executable_code",
            part.executableCode().map(DebugLoggingPlugin::serializeExecutableCode)));
  }

  private static ImmutableMap<String, Object> serializeCall(FunctionCall call) {
    return dataOf(
        field("id", call.id()),
        field("name", call.name()),
        field("args", call.args().map(DebugLoggingPlugin::safeSerializeMap)));
  }

  private static ImmutableMap<String, Object> serializeFunctionResponse(FunctionResponse response) {
    return dataOf(
        field("id", response.id()),
        field("name", response.name()),
        field("response", response.response().map(DebugLoggingPlugin::safeSerializeMap)));
  }

  private static ImmutableMap<String, Object> serializeInlineData(Blob blob) {
    // The bytes are omitted to keep the file size manageable.
    return dataOf(
        field("mime_type", blob.mimeType()),
        field("display_name", blob.displayName()),
        field("_data_omitted", true));
  }

  private static ImmutableMap<String, Object> serializeFileData(FileData fileData) {
    return dataOf(field("file_uri", fileData.fileUri()), field("mime_type", fileData.mimeType()));
  }

  private static ImmutableMap<String, Object> serializeCodeExecutionResult(
      CodeExecutionResult result) {
    return dataOf(
        field("outcome", result.outcome().map(String::valueOf)), field("output", result.output()));
  }

  private static ImmutableMap<String, Object> serializeExecutableCode(ExecutableCode code) {
    return dataOf(
        field("language", code.language().map(String::valueOf)), field("code", code.code()));
  }

  private static @Nullable Object safeSerialize(@Nullable Object value, Set<Object> path) {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    if (value instanceof byte[] bytes) {
      return BYTES_MARKER.formatted(bytes.length);
    }
    if (value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof Object[]) {
      return copyContainer(value, path);
    }
    return describe(value);
  }

  /**
   * Copies a map, collection or array, writing {@link #CIRCULAR_REFERENCE} for a container that
   * contains itself.
   *
   * @param path the containers currently being copied, compared by identity
   */
  private static Object copyContainer(Object container, Set<Object> path) {
    if (!path.add(container)) {
      return CIRCULAR_REFERENCE;
    }
    Object copy =
        container instanceof Map<?, ?> map
            ? safeSerializeMap(map, path)
            : safeSerializeList(items(container), path);
    path.remove(container);
    return copy;
  }

  private static Collection<?> items(Object container) {
    return container instanceof Object[] array ? Arrays.asList(array) : (Collection<?>) container;
  }

  private static Map<String, Object> safeSerializeMap(Map<?, ?> map) {
    Set<Object> path = newSetFromMap(new IdentityHashMap<>());
    path.add(map);
    return safeSerializeMap(map, path);
  }

  private static Map<String, Object> safeSerializeMap(Map<?, ?> map, Set<Object> path) {
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, value) -> copy.put(String.valueOf(key), safeSerialize(value, path)));
    return copy;
  }

  private static List<Object> safeSerializeList(Collection<?> values, Set<Object> path) {
    List<Object> copy = new ArrayList<>();
    values.forEach(value -> copy.add(safeSerialize(value, path)));
    return copy;
  }

  private static String describe(Object value) {
    try {
      return String.valueOf(value);
    } catch (RuntimeException e) {
      return UNSERIALIZABLE_MARKER;
    }
  }

  /** Builds an ordered map that omits null values and empty Optionals, unwrapping present ones. */
  @SafeVarargs
  private static ImmutableMap<String, Object> dataOf(Map.Entry<String, ?>... fields) {
    Map<String, Object> data = new LinkedHashMap<>();
    for (Map.Entry<String, ?> field : fields) {
      unwrap(field.getValue()).ifPresent(value -> data.put(field.getKey(), value));
    }
    return ImmutableMap.copyOf(data);
  }

  private static Map.Entry<String, ?> field(String key, @Nullable Object value) {
    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  private static Optional<?> unwrap(@Nullable Object value) {
    return value instanceof Optional<?> optional ? optional : Optional.ofNullable(value);
  }

  private static <M extends Map<?, ?>> Optional<M> nonEmpty(M map) {
    return Optional.of(map).filter(not(Map::isEmpty));
  }

  private record DebugEntry(
      String timestamp,
      String entryType,
      String invocationId,
      @Nullable String agentName,
      ImmutableMap<String, Object> data) {}

  private record InvocationDebugState(
      String invocationId,
      String sessionId,
      @Nullable String appName,
      @Nullable String userId,
      String startTime,
      Queue<DebugEntry> entries) {}
}
