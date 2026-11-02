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

package com.google.adk.workflow;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.Role;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Rebuilds the progress of workflow nodes from the session events of their invocation, and decides
 * how a node that already ran in an earlier turn resumes. {@link #scan} folds the event log into
 * {@link RecoveredNode}s; when a workflow or {@link Context#runNode} reaches a node, {@link
 * #intercept} decides whether it runs again or replays its earlier result through {@link
 * #replayContext}.
 */
@Experimental
final class ResumeScan {

  private static final String RESULT_KEY = "result";
  // Stands for text that is not JSON, since the JSON text null reads as a null value.
  private static final Object NOT_JSON = new Object();
  private static final ObjectReader STRICT_JSON =
      JsonBaseModel.getMapper()
          .readerFor(Object.class)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private ResumeScan() {}

  /**
   * Reconstructs the direct children of the node at {@code basePath} from the session events of the
   * invocation of {@code context}, as {@link #scan(List, String, String)} does.
   */
  static ImmutableMap<String, RecoveredNode> scan(InvocationContext context, String basePath) {
    return scan(context.session().immutableEvents(), basePath, context.invocationId());
  }

  /**
   * Reconstructs the direct children of the node at {@code basePath} from the final events of
   * {@code invocationId} in one forward pass, keyed by their {@code name@runId} segment. An empty
   * base path reconstructs root nodes. A user event can only answer an interrupt; any other event
   * reports the progress of the node that emitted it.
   */
  static ImmutableMap<String, RecoveredNode> scan(
      List<Event> events, String basePath, String invocationId) {
    Reconstruction reconstruction = new Reconstruction(basePath);
    for (Event event : events) {
      if (!invocationId.equals(event.invocationId()) || event.partial().orElse(false)) {
        continue;
      }
      if (Objects.equals(event.author(), Role.USER)) {
        reconstruction.recordAnswers(event);
      } else {
        reconstruction.recordNodeEvent(event);
      }
    }
    return ImmutableMap.copyOf(reconstruction.recovered);
  }

  /**
   * Decides whether {@code node} runs again or replays its result from {@code recovered}. In order:
   * a node with unanswered interrupts waits on them, unless it reruns on resume and has answers; a
   * node that failed, in {@code recovered} or {@code failedThisTurn}, reruns; a recorded output,
   * route or transfer replays; a node {@code canceledMidRun} in this turn reruns; an answered node
   * that does not rerun on resume completes with its answers. Otherwise a node that reruns on
   * resume reruns unless it finished since its last answer, and a {@code dynamic} node reruns while
   * a graph node does not.
   */
  static Interception intercept(
      Node node,
      @Nullable RecoveredNode recovered,
      boolean dynamic,
      boolean canceledMidRun,
      boolean failedThisTurn) {
    if (recovered == null) {
      return Interception.run(ImmutableMap.of());
    }
    Map<String, @Nullable Object> resolved = copyOf(recovered.resolvedResponses);
    if (node instanceof Workflow) {
      // A workflow replays its own children, so it always runs.
      return Interception.run(resolved);
    }
    String transfer = recovered.transferToAgent;
    String branch = recovered.branch;
    ImmutableSet<String> unresolved = recovered.unresolved();

    if (!unresolved.isEmpty()) {
      return node.rerunOnResume() && !resolved.isEmpty()
          ? Interception.run(resolved)
          : Interception.waitOn(branch, unresolved, transfer);
    }
    // A node that takes on a child's failure leaves no error event, so this turn records it.
    if (recovered.failed || failedThisTurn) {
      return Interception.run(resolved);
    }
    Object output = recovered.output;
    if (output != null || recovered.route != null || transfer != null) {
      return Interception.replay(
          output instanceof Content content ? processRehydratedOutput(node, content) : output,
          recovered.route,
          branch,
          transfer);
    }
    // A node canceled mid-run in this turn left progress events but no result.
    if (canceledMidRun) {
      return Interception.run(resolved);
    }
    if (!recovered.interruptIds.isEmpty() && !node.rerunOnResume()) {
      Object answers = resolved.size() == 1 ? resolved.values().iterator().next() : resolved;
      return Interception.replay(answers, /* route= */ null, branch, /* transferToAgent= */ null);
    }
    // A rerun node that finished without an output stays done, so its side effects don't repeat.
    boolean shouldRun =
        node.waitForOutput() || (node.rerunOnResume() ? !recovered.finishedAfterResume : dynamic);
    return shouldRun
        ? Interception.run(resolved)
        : Interception.replay(
            /* output= */ null, /* route= */ null, branch, /* transferToAgent= */ null);
  }

  /**
   * Returns the context that {@code node} would have finished with, carrying the replayed result of
   * {@code interception} without running the node. The node replays on the branch it recorded, or
   * else on the branch of {@code scheduled}.
   */
  static Context replayContext(
      Context parent,
      InvocationContext scheduled,
      Node node,
      String runId,
      boolean useAsOutput,
      Interception interception) {
    InvocationContext invocationContext =
        interception.branch() == null
            ? scheduled
            : scheduled.toBuilder().branch(interception.branch()).build();
    Context context =
        new Context(
            invocationContext,
            NodeExecutionState.builder()
                .node(node)
                .eventSink(parent.requireNodeState().eventSink())
                .parent(parent)
                .runId(runId)
                .useAsOutput(useAsOutput)
                .build());
    interception.replayOn(context);
    return context;
  }

  /**
   * Returns the output of a node whose content was its output: the content's text, read as JSON
   * when the node has an output schema that does not accept a string. Blank text is no output.
   *
   * @throws IllegalArgumentException if the schema needs JSON and the text is not JSON
   */
  static @Nullable Object processRehydratedOutput(Node node, Content content) {
    String text = textOf(content).trim();
    if (text.isEmpty()) {
      return null;
    }
    Optional<Schema> schema = node.outputSchema();
    // Model text is raw rather than JSON-quoted, so it stays text when the schema accepts strings.
    if (schema.isEmpty() || acceptsString(schema.get())) {
      return text;
    }
    Object value = readJson(text);
    if (value == NOT_JSON) {
      throw new IllegalArgumentException(
          "Validation failed for rehydrated output against schema: value is not valid JSON.");
    }
    return value;
  }

  /**
   * Unwraps and validates the answer {@code response} to interrupt {@code interruptId}. A {@code
   * {"result": value}} envelope is unwrapped, and a string value inside it is read as JSON unless
   * {@code schema} accepts a string.
   *
   * @throws IllegalArgumentException if the answer does not match {@code schema}
   */
  static @Nullable Object validateAnswer(
      @Nullable Schema schema, @Nullable Object response, String interruptId) {
    Object answer = unwrapAnswer(schema, response);
    if (schema == null) {
      return answer;
    }
    String mismatch = valueMismatch(answer, schema);
    if (mismatch != null) {
      throw new IllegalArgumentException(
          "Validation failed for interrupt " + interruptId + ": " + mismatch);
    }
    return answer;
  }

  private static @Nullable Object unwrapAnswer(@Nullable Schema schema, @Nullable Object response) {
    if (!(response instanceof Map<?, ?> map) || map.size() != 1 || !map.containsKey(RESULT_KEY)) {
      return response;
    }
    Object value = map.get(RESULT_KEY);
    if (!(value instanceof String text) || (schema != null && acceptsString(schema))) {
      return value;
    }
    Object json = readJson(text);
    return json == NOT_JSON ? text : json;
  }

  /** Returns whether {@code schema} accepts a string, directly or through an {@code anyOf}. */
  private static boolean acceptsString(Schema schema) {
    return typeOf(schema) == Type.Known.STRING
        || schema.anyOf().orElse(ImmutableList.of()).stream().anyMatch(ResumeScan::acceptsString);
  }

  /** Reads {@code text} as strict JSON, or returns {@link #NOT_JSON} if it is not JSON. */
  private static @Nullable Object readJson(String text) {
    try {
      return STRICT_JSON.readValue(text);
    } catch (JsonProcessingException e) {
      return NOT_JSON;
    }
  }

  /**
   * Returns why {@code value} does not match {@code schema}, or null if it does. The reason names
   * parts of the schema but never the value, which comes from the user.
   */
  private static @Nullable String valueMismatch(@Nullable Object value, Schema schema) {
    if (value == null && schema.nullable().orElse(false)) {
      return null;
    }
    // Check maps before anyOf so each member is validated as an object with its required keys.
    if (value instanceof Map<?, ?> map) {
      return mapMismatch(map, schema);
    }
    List<Schema> anyOf = schema.anyOf().orElse(ImmutableList.of());
    if (!anyOf.isEmpty()
        && anyOf.stream().allMatch(member -> valueMismatch(value, member) != null)) {
      return "it matches none of the schema's anyOf alternatives";
    }
    Type.Known type = typeOf(schema);
    if (type == null || type == Type.Known.TYPE_UNSPECIFIED) {
      return null;
    }
    boolean matches =
        switch (type) {
          case STRING -> value instanceof String;
          case INTEGER ->
              value instanceof Integer
                  || value instanceof Long
                  || value instanceof Short
                  || value instanceof Byte
                  || value instanceof BigInteger;
          case NUMBER -> value instanceof Number;
          case BOOLEAN -> value instanceof Boolean;
          case NULL -> value == null;
          case ARRAY -> {
            if (!(value instanceof Collection<?> items)) {
              yield false;
            }
            Optional<Schema> itemSchema = schema.items();
            yield itemSchema.isEmpty()
                || items.stream().allMatch(item -> valueMismatch(item, itemSchema.get()) == null);
          }
          case OBJECT -> false;
          default -> true;
        };
    return matches ? null : "it is not of the schema's type " + type;
  }

  /** Returns why {@code map} does not match {@code schema}, or null if it does. */
  private static @Nullable String mapMismatch(Map<?, ?> map, Schema schema) {
    Type.Known type = typeOf(schema);
    if (type != null && type != Type.Known.OBJECT && type != Type.Known.TYPE_UNSPECIFIED) {
      return "the schema's type is " + type + ", not an object";
    }
    List<Schema> anyOf = schema.anyOf().orElse(ImmutableList.of());
    if (!anyOf.isEmpty() && anyOf.stream().allMatch(member -> mapMismatch(map, member) != null)) {
      return "it matches none of the schema's anyOf alternatives";
    }
    Optional<Map<String, Schema>> properties = schema.properties();
    if (properties.isPresent()) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Schema property = properties.get().get(String.valueOf(entry.getKey()));
        // An undeclared key comes from the user, so the reason does not name it.
        if (property == null) {
          return "it has a key the schema does not declare";
        }
        if (valueMismatch(entry.getValue(), property) != null) {
          return "property '" + entry.getKey() + "' does not match its schema";
        }
      }
    }
    for (String required : schema.required().orElse(ImmutableList.of())) {
      if (!map.containsKey(required)) {
        return "required property '" + required + "' is missing";
      }
    }
    return null;
  }

  private static Type.@Nullable Known typeOf(Schema schema) {
    return schema.type().map(Type::knownEnum).orElse(null);
  }

  /** Returns the text of the non-thought parts of {@code content}. */
  private static String textOf(Content content) {
    StringBuilder text = new StringBuilder();
    for (Part part : content.parts().orElse(ImmutableList.of())) {
      if (!part.thought().orElse(false)) {
        part.text().ifPresent(text::append);
      }
    }
    return text.toString();
  }

  /** Reads the response schema that an {@value RequestInput#FUNCTION_CALL_NAME} call carried. */
  private static @Nullable Schema readSchema(@Nullable Object value) {
    try {
      return JsonBaseModel.getMapper().convertValue(value, Schema.class);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Map<String, @Nullable Object> copyOf(Map<String, @Nullable Object> map) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }

  /**
   * The execution state of a node reconstructed from an earlier turn of its invocation. Only the
   * scan that creates it updates it.
   */
  static final class RecoveredNode {
    @Nullable Object output;
    boolean failed;
    @Nullable ImmutableList<Route> route;
    @Nullable String branch;
    @Nullable String transferToAgent;
    final Set<String> interruptIds = new LinkedHashSet<>();
    final Map<String, @Nullable Object> resolvedResponses = new LinkedHashMap<>();

    /** Whether the node's last direct event, since its last answer, shows that it finished. */
    boolean finishedAfterResume;

    /** Returns the IDs of the interrupts the node raised that are not answered yet. */
    ImmutableSet<String> unresolved() {
      return Sets.difference(interruptIds, resolvedResponses.keySet()).immutableCopy();
    }

    /** Records {@code answer}; what the node emitted before it asked is not its result. */
    void recordAnswer(String interruptId, @Nullable Object answer) {
      output = null;
      finishedAfterResume = false;
      resolvedResponses.put(interruptId, answer);
    }
  }

  /**
   * How a node that already ran resumes: whether it runs again with {@code resumeInputs}, keyed by
   * interrupt ID, or replays its result on {@code branch}, where null keeps its scheduled branch.
   */
  record Interception(
      boolean shouldRun,
      @Nullable Object output,
      @Nullable ImmutableList<Route> route,
      @Nullable String branch,
      ImmutableSet<String> interrupts,
      Map<String, @Nullable Object> resumeInputs,
      @Nullable String transferToAgent) {

    /** The node runs again and reads {@code resumeInputs}. */
    static Interception run(Map<String, @Nullable Object> resumeInputs) {
      return new Interception(
          /* shouldRun= */ true,
          /* output= */ null,
          /* route= */ null,
          /* branch= */ null,
          ImmutableSet.of(),
          resumeInputs,
          /* transferToAgent= */ null);
    }

    /** The node still waits on its unanswered {@code interrupts}. */
    static Interception waitOn(
        @Nullable String branch,
        ImmutableSet<String> interrupts,
        @Nullable String transferToAgent) {
      return new Interception(
          /* shouldRun= */ false,
          /* output= */ null,
          /* route= */ null,
          branch,
          interrupts,
          ImmutableMap.of(),
          transferToAgent);
    }

    /** The node replays the result it recorded, without running. */
    static Interception replay(
        @Nullable Object output,
        @Nullable ImmutableList<Route> route,
        @Nullable String branch,
        @Nullable String transferToAgent) {
      return new Interception(
          /* shouldRun= */ false,
          output,
          route,
          branch,
          ImmutableSet.of(),
          ImmutableMap.of(),
          transferToAgent);
    }

    /** Records the replayed result on {@code context} without sending it. */
    void replayOn(Context context) {
      NodeExecutionState state = context.requireNodeState();
      if (output != null) {
        state.produceOutput(output);
      }
      if (route != null) {
        state.selectRoutes(route);
      }
      if (transferToAgent != null) {
        context.eventActions().setTransferToAgent(transferToAgent);
      }
      state.addInterruptIds(interrupts);
    }
  }

  /**
   * One pass over an event log. An event belongs to the {@code name@runId} segment of the direct
   * child of {@code basePath} that it comes from.
   */
  private static final class Reconstruction {
    private final String basePath;
    private final String childPrefix;

    /** Reconstructed nodes keyed by their owner, in the order they first appear. */
    final Map<String, RecoveredNode> recovered = new LinkedHashMap<>();

    /** The node that raised each interrupt, so a later answer finds its owner. */
    private final Map<String, RecoveredNode> interruptOwner = new HashMap<>();

    /** The response schema of each request for input, to validate its answer. */
    private final Map<String, Schema> schemas = new HashMap<>();

    Reconstruction(String basePath) {
      this.basePath = basePath;
      this.childPrefix = basePath.isEmpty() ? "" : basePath + "/";
    }

    /** Attributes {@code event} to its owner and records what it says about that node. */
    void recordNodeEvent(Event event) {
      String eventPath = event.nodeInfo().map(NodeInfo::path).orElse("");
      String key = ownerKey(eventPath);
      if (key == null) {
        return;
      }
      String ownerPath = childPrefix + key;
      // False for an event that a descendant of the owner emitted.
      boolean isDirect = eventPath.equals(ownerPath);
      RecoveredNode node = recovered.computeIfAbsent(key, unused -> new RecoveredNode());
      // The finish check below reads the failure and the interrupts that these record.
      recordOutcome(event, isDirect, ownerPath, node);
      boolean raisedAny = recordRaisedInterrupts(event, node);
      if (isDirect) {
        node.finishedAfterResume =
            !raisedAny
                && !node.failed
                && event.functionCalls().isEmpty()
                && event.functionResponses().isEmpty();
      }
    }

    /**
     * Records the answers that the function responses of the user event {@code event} carry. A
     * response whose ID matches no interrupt can still answer an interrupt raised on a tool
     * sub-branch named after that interrupt, such as {@code wf@1.task@ask_1} for {@code ask_1}.
     */
    void recordAnswers(Event event) {
      for (FunctionResponse response : event.functionResponses()) {
        String id = response.id().orElse(null);
        if (id == null) {
          continue;
        }
        RecoveredNode owner = interruptOwner.get(id);
        ImmutableMap<String, RecoveredNode> targets =
            owner != null
                ? ImmutableMap.of(id, owner)
                : interruptsMatchingBranch(event.branch().orElse(null));
        if (targets.isEmpty()) {
          continue;
        }
        Object answer = validateAnswer(schemas.get(id), response.response().orElse(null), id);
        targets.forEach((interruptId, node) -> node.recordAnswer(interruptId, answer));
      }
    }

    /** Returns the raised interrupts whose IDs are run IDs of {@code branch}'s segments. */
    private ImmutableMap<String, RecoveredNode> interruptsMatchingBranch(@Nullable String branch) {
      if (isNullOrEmpty(branch)) {
        return ImmutableMap.of();
      }
      Set<String> branchRunIds = new HashSet<>();
      for (String segment : Splitter.on('.').split(branch)) {
        int at = segment.lastIndexOf('@');
        if (at >= 0 && at < segment.length() - 1) {
          branchRunIds.add(segment.substring(at + 1));
        }
      }
      Map<String, RecoveredNode> targets = new LinkedHashMap<>();
      for (RecoveredNode node : recovered.values()) {
        for (String interruptId : node.interruptIds) {
          if (branchRunIds.contains(interruptId)) {
            targets.put(interruptId, node);
          }
        }
      }
      return ImmutableMap.copyOf(targets);
    }

    /** Returns the key that owns an event at {@code eventPath}, or null if it is outside. */
    private @Nullable String ownerKey(String eventPath) {
      if (eventPath.equals(basePath) || !eventPath.startsWith(childPrefix)) {
        return null;
      }
      String rest = eventPath.substring(childPrefix.length());
      int slash = rest.indexOf('/');
      return slash < 0 ? rest : rest.substring(0, slash);
    }

    /**
     * Records the output, routes, transfer and failure that {@code event} shows for the node at
     * {@code ownerPath}. A descendant's event counts only if the descendant's output is also the
     * owner's, as with {@code useAsOutput}.
     */
    private static void recordOutcome(
        Event event, boolean isDirect, String ownerPath, RecoveredNode node) {
      Object eventOutput = event.output().orElse(null);
      if (eventOutput == null && event.nodeInfo().map(NodeInfo::messageAsOutput).orElse(false)) {
        eventOutput = event.content().orElse(null);
      }
      boolean isDelegated =
          eventOutput != null
              && event.nodeInfo().map(info -> info.outputFor().contains(ownerPath)).orElse(false);
      if (!isDirect && !isDelegated) {
        return;
      }
      if (eventOutput != null) {
        node.output = eventOutput;
        // A delegate's output event carries the delegate's branch, not this node's.
        if (isDirect && event.output().isPresent()) {
          node.branch = event.branch().orElse(null);
        }
      }
      Optional<ImmutableList<Route>> route = event.actions().route();
      Optional<String> transfer = event.actions().transferToAgent();
      route.ifPresent(routes -> node.route = routes);
      transfer.ifPresent(agent -> node.transferToAgent = agent);
      // A result or a later event without an error clears a failure; a failure drops the result.
      boolean hasResult = eventOutput != null || route.isPresent() || transfer.isPresent();
      if (hasResult || event.errorCode().isEmpty()) {
        node.failed = false;
      } else {
        node.output = null;
        node.route = null;
        node.transferToAgent = null;
        node.failed = true;
      }
    }

    /**
     * Records the interrupts {@code event} raises for {@code node} (long-running calls and requests
     * for input, together with their response schemas) and returns whether it raised any.
     */
    private boolean recordRaisedInterrupts(Event event, RecoveredNode node) {
      Set<String> raised =
          new LinkedHashSet<>(event.longRunningToolIds().orElse(ImmutableSet.of()));
      for (FunctionCall call : event.functionCalls()) {
        if (call.id().isEmpty()
            || !call.name().orElse("").equals(RequestInput.FUNCTION_CALL_NAME)) {
          continue;
        }
        String id = call.id().get();
        raised.add(id);
        Schema schema =
            readSchema(
                call.args().map(args -> args.get(RequestInput.RESPONSE_SCHEMA_KEY)).orElse(null));
        if (schema != null) {
          schemas.put(id, schema);
        }
      }
      for (String id : raised) {
        // An interrupt raised again waits for a new answer, as in Python.
        node.resolvedResponses.remove(id);
        node.interruptIds.add(id);
        interruptOwner.put(id, node);
      }
      return !raised.isEmpty();
    }
  }
}
