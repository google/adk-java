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

package com.google.adk.flows.llmflows;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.Role;
import com.google.adk.events.Event;
import com.google.adk.events.EventCompaction;
import com.google.adk.models.LlmRequest;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** {@link RequestProcessor} that populates content in request for LLM flows. */
public final class Contents implements RequestProcessor {
  public Contents() {}

  @Override
  @SuppressWarnings("deprecation") // Framework reads the opt-in workaround flag.
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent)) {
      return Single.just(
          RequestProcessor.RequestProcessingResult.create(
              request, context.session().immutableEvents()));
    }
    LlmAgent llmAgent = (LlmAgent) context.agent();

    String modelName;
    try {
      modelName = llmAgent.resolvedModel().modelName().orElse("");
    } catch (IllegalStateException e) {
      modelName = "";
    }
    // Explicit override applies to all models; when unset, group by default for Gemini 3.
    boolean groupFunctionResponses =
        context
            .runConfig()
            .groupFunctionResponsesInHistoryOverride()
            .orElse(modelName.contains("gemini-3"));

    ImmutableList<Event> sessionEvents = context.session().immutableEvents();

    if (llmAgent.includeContents() == LlmAgent.IncludeContents.NONE) {
      return Single.just(
          RequestProcessor.RequestProcessingResult.create(
              request.toBuilder()
                  .contents(
                      getCurrentTurnContents(
                          context.branch().orElse(null),
                          sessionEvents,
                          context.agent().name(),
                          groupFunctionResponses))
                  .build(),
              ImmutableList.of()));
    }

    ImmutableList<Content> contents =
        getContents(
            context.branch().orElse(null),
            sessionEvents,
            context.agent().name(),
            groupFunctionResponses);

    return Single.just(
        RequestProcessor.RequestProcessingResult.create(
            request.toBuilder().contents(contents).build(), ImmutableList.of()));
  }

  /** Gets contents for the current turn only (no conversation history). */
  private ImmutableList<Content> getCurrentTurnContents(
      @Nullable String currentBranch,
      List<Event> events,
      String agentName,
      boolean groupFunctionResponses) {
    // Find the latest event that starts the current turn and process from there.
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      TranscriptionKind kind = transcriptionKind(event);
      int start = -1;
      if (kind != null) {
        start = spokenTurnStart(currentBranch, events, agentName, i, kind);
      } else if (shouldIncludeEventInContext(currentBranch, event)
          && (event.author().equals(Role.USER) || isOtherAgentReply(agentName, event))) {
        start = i;
      }
      if (start >= 0) {
        return getContents(
            currentBranch, events.subList(start, events.size()), agentName, groupFunctionResponses);
      }
    }
    return ImmutableList.of();
  }

  /**
   * Returns where the spoken run ending at {@code end} starts, so the turn keeps the whole run
   * rather than its last chunk. Returns -1 when the run does not start a turn: the agent's own
   * speech, a chunk outside the context, or a blank run, which the merge drops.
   */
  private int spokenTurnStart(
      @Nullable String currentBranch,
      List<Event> events,
      String agentName,
      int end,
      TranscriptionKind kind) {
    Event event = events.get(end);
    boolean startsTurn = kind == TranscriptionKind.INPUT || isOtherAgentReply(agentName, event);
    if (!startsTurn || !shouldIncludeEventInContext(currentBranch, event)) {
      return -1;
    }
    int start = end;
    boolean blank = kind.text(event).isBlank();
    for (int i = end - 1; i >= 0; i--) {
      // Events left out of the context do not break the run.
      if (!shouldIncludeEventInContext(currentBranch, events.get(i))) {
        continue;
      }
      if (!continuesRun(kind, events.get(start), events.get(i))) {
        break;
      }
      start = i;
      blank &= kind.text(events.get(i)).isBlank();
    }
    return blank ? -1 : start;
  }

  private ImmutableList<Content> getContents(
      @Nullable String currentBranch,
      List<Event> events,
      String agentName,
      boolean groupFunctionResponses) {
    List<Event> filteredEvents = new ArrayList<>();
    boolean hasCompactEvent = false;

    for (Event event : events) {
      if (shouldIncludeEventInContext(currentBranch, event)) {
        hasCompactEvent |= event.actions().compaction().isPresent();
        filteredEvents.add(event);
      }
    }

    if (hasCompactEvent) {
      List<Event> compactedEvents = processCompactionEvent(filteredEvents, agentName);
      filteredEvents = recoverCompactedFunctionCalls(compactedEvents, filteredEvents);
    }

    // Merge after compaction, which may end mid-run, and before other agents are narrated.
    List<Event> resultEvents = new ArrayList<>();
    for (Event event : mergeTranscriptions(filteredEvents)) {
      if (isOtherAgentReply(agentName, event)) {
        Event foreignEvent = convertForeignEvent(event);
        if (foreignEvent != null) {
          resultEvents.add(foreignEvent);
        }
      } else {
        resultEvents.add(event);
      }
    }

    resultEvents = rearrangeEventsForLatestFunctionResponse(resultEvents);
    resultEvents =
        rearrangeEventsForAsyncFunctionResponsesInHistory(resultEvents, groupFunctionResponses);

    return resultEvents.stream()
        .map(Event::content)
        .flatMap(Optional::stream)
        .collect(toImmutableList());
  }

  /**
   * Whether {@code event} goes into the LLM context. Compaction events always do, so {@link
   * #processCompactionEvent} can apply them.
   */
  private boolean shouldIncludeEventInContext(@Nullable String currentBranch, Event event) {
    if (event.actions().compaction().isPresent()) {
      return true;
    }
    // TODO: Skip auth events.
    return !isEmptyContent(event)
        && isEventBelongsToBranch(currentBranch, event)
        && !isRequestConfirmationEvent(event);
  }

  /**
   * Check if an event has missing or empty content.
   *
   * <p>This can happen to the events that only changed session state. When the content is empty and
   * no transcription has text, the event will be considered as empty. The content is considered
   * empty if none of its parts contain text, inline data, file data, function call, function
   * response, server-side tool call, or server-side tool response. Parts with only thoughts are
   * also considered empty.
   *
   * @param event the event to check.
   * @return {@code true} if the event is considered to have empty content, {@code false} otherwise.
   */
  private boolean isEmptyContent(Event event) {
    return !hasVisibleContent(event) && transcriptionKind(event) == null;
  }

  /** Whether {@code event} has a role and at least one part that is visible in the LLM context. */
  private boolean hasVisibleContent(Event event) {
    return event
        .content()
        .filter(content -> !content.role().orElse("").isEmpty())
        .flatMap(Content::parts)
        .map(parts -> parts.stream().anyMatch(part -> !isPartInvisible(part)))
        .orElse(false);
  }

  /**
   * Merges each spoken run into one text event, so later invocations replay what was said: input as
   * a user turn, output as a model turn. A blank run is dropped.
   */
  private ImmutableList<Event> mergeTranscriptions(List<Event> events) {
    ImmutableList.Builder<Event> merged = ImmutableList.builder();
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < events.size(); i++) {
      Event event = events.get(i);
      TranscriptionKind kind = transcriptionKind(event);
      if (kind == null) {
        merged.add(event);
        continue;
      }
      text.append(kind.text(event));
      if (i + 1 < events.size() && continuesRun(kind, event, events.get(i + 1))) {
        continue;
      }
      // A blank run says nothing, while a space chunk inside a run still joins the words around it.
      String runText = text.toString();
      if (!runText.isBlank()) {
        merged.add(kind.toTextEvent(event, runText));
      }
      text.setLength(0);
    }
    return merged.build();
  }

  /**
   * Whether {@code other} belongs to the {@code kind} run that {@code event} is in. An output run
   * is one agent's speech; an input run is the user's even when stored under different agents.
   */
  private boolean continuesRun(TranscriptionKind kind, Event event, Event other) {
    return transcriptionKind(other) == kind
        && (kind == TranscriptionKind.INPUT || event.author().equals(other.author()));
  }

  /**
   * The kind of transcription {@code event} is merged as, or null if it has visible content or no
   * transcription text.
   */
  private @Nullable TranscriptionKind transcriptionKind(Event event) {
    if (hasVisibleContent(event)) {
      return null;
    }
    if (!TranscriptionKind.INPUT.text(event).isEmpty()) {
      return TranscriptionKind.INPUT;
    }
    if (!TranscriptionKind.OUTPUT.text(event).isEmpty()) {
      return TranscriptionKind.OUTPUT;
    }
    return null;
  }

  /** Which side of a live conversation a transcription records. */
  private enum TranscriptionKind {
    INPUT,
    OUTPUT;

    String text(Event event) {
      Optional<Transcription> transcription =
          switch (this) {
            case INPUT -> event.inputTranscription();
            case OUTPUT -> event.outputTranscription();
          };
      return transcription.flatMap(Transcription::text).orElse("");
    }

    /** The run's last {@code event}, carrying the run's {@code text} instead of a transcription. */
    Event toTextEvent(Event event, String text) {
      return switch (this) {
        // A live run stores the user's speech under the listening agent's name.
        case INPUT ->
            event.toBuilder()
                .author(Role.USER)
                .content(Content.builder().role(Role.USER).parts(Part.fromText(text)).build())
                .inputTranscription(null)
                .build();
        case OUTPUT ->
            event.toBuilder()
                .content(Content.builder().role("model").parts(Part.fromText(text)).build())
                .outputTranscription(null)
                .build();
      };
    }
  }

  /**
   * Returns whether a part is invisible for LLM context.
   *
   * <p>A part is invisible if:
   *
   * <ul>
   *   <li>It has no meaningful content (text, inline_data, file_data, function_call,
   *       function_response, tool_call, tool_response, executable_code, or code_execution_result)
   *       and no thought_signature, OR
   *   <li>It is marked as a thought AND does not contain function_call, function_response,
   *       tool_call, tool_response or thought_signature
   * </ul>
   *
   * <p>Function calls and responses are never invisible, even if marked as thought, because they
   * represent actions that need to be executed or results that need to be processed. Parts carrying
   * a thought signature, and server-side tool calls and their responses, are never invisible
   * either, because the caller is required to echo them back on the next request.
   *
   * @param part the part to check.
   * @return {@code true} if the part is invisible, {@code false} otherwise.
   */
  private boolean isPartInvisible(Part part) {
    if (part.functionCall().isPresent() || part.functionResponse().isPresent()) {
      return false;
    }

    // A thought signature is opaque state to hand back verbatim, and it routinely arrives on a part
    // with nothing else in it, so it has to be checked before the emptiness test below.
    if (part.thoughtSignature().map(signature -> signature.length > 0).orElse(false)) {
      return false;
    }

    // Server-side tool calls/responses must be echoed back to the model.
    if (part.toolCall().isPresent() || part.toolResponse().isPresent()) {
      return false;
    }

    return part.thought().orElse(false)
        || !(part.text().isPresent()
            || part.inlineData().isPresent()
            || part.fileData().isPresent()
            || part.codeExecutionResult().isPresent()
            || part.executableCode().isPresent());
  }

  /**
   * Filters events that are covered by compaction events by identifying compacted ranges and
   * filters out events that are covered by compaction summaries. Also filters out redundant
   * compaction events (i.e., those fully covered by a later compaction event).
   *
   * <p>Compaction events are inserted into the stream relative to the events they cover.
   * Specifically, a compaction event is placed immediately before the first retained event that
   * follows the compaction range (or at the end of the covered range if no events are retained).
   * This ensures a logical flow of "Summary of History" -> "Recent/Retained Events".
   *
   * <p><b>Case 1: Sliding Window + Retention</b>
   *
   * <p>Compaction events have some overlap but do not fully cover each other. Therefore, all
   * compaction events are preserved, as well as the final retained events.
   *
   * <pre>
   * [
   *   event_1(timestamp=1),
   *   event_2(timestamp=2),
   *   compaction_1(event_1, event_2, timestamp=3, content=summary_1_2, startTime=1, endTime=2),
   *   event_3(timestamp=4),
   *   compaction_2(event_2, event_3, timestamp=5, content=summary_2_3, startTime=2, endTime=4),
   *   event_4(timestamp=6)
   * ]
   * </pre>
   *
   * Will result in the following events output
   *
   * <pre>
   * [
   *   compaction_1,
   *   compaction_2
   *   event_4
   * ]
   * </pre>
   *
   * <p><b>Case 2: Rolling Summary + Retention</b>
   *
   * <p>The newer compaction event fully covers the older one. Therefore, the older compaction event
   * is removed, leaving only the latest summary and the final retained events.
   *
   * <pre>
   * [
   *   event_1(timestamp=1),
   *   event_2(timestamp=2),
   *   event_3(timestamp=3),
   *   event_4(timestamp=4),
   *   compaction_1(event_1, timestamp=5, content=summary_1, startTime=1, endTime=1),
   *   event_6(timestamp=6),
   *   event_7(timestamp=7),
   *   compaction_2(compaction_1, event_2, event_3, timestamp=8, content=summary_1_3, startTime=1, endTime=3),
   *   event_9(timestamp=9)
   * ]
   * </pre>
   *
   * Will result in the following events output
   *
   * <pre>
   * [
   *   compaction_2,
   *   event_4,
   *   event_6,
   *   event_7,
   *   event_9
   * ]
   * </pre>
   *
   * @param events the list of event to filter.
   * @param agentName the current agent, which authors each summary.
   * @return a new list with compaction applied.
   */
  private List<Event> processCompactionEvent(List<Event> events, String agentName) {
    // Step 1: Split events into compaction events and regular events.
    List<Event> compactionEvents = new ArrayList<>();
    List<Event> regularEvents = new ArrayList<>();
    for (Event event : events) {
      if (event.actions().compaction().isPresent()) {
        compactionEvents.add(event);
      } else {
        regularEvents.add(event);
      }
    }

    // Step 2: Remove redundant compaction events (overlapping ones).
    compactionEvents = removeOverlappingCompactions(compactionEvents);

    // Step 3: Merge regular events and compaction events based on timestamps.
    // We iterate backwards from the latest to the earliest event.
    List<Event> result = new ArrayList<>();
    int c = compactionEvents.size() - 1;
    int e = regularEvents.size() - 1;
    while (e >= 0 && c >= 0) {
      Event event = regularEvents.get(e);
      EventCompaction compaction = compactionEvents.get(c).actions().compaction().get();

      if (event.timestamp() >= compaction.startTimestamp()
          && event.timestamp() <= compaction.endTimestamp()) {
        // If the event is covered by compaction, skip it.
        e--;
      } else if (event.timestamp() > compaction.endTimestamp()) {
        // If the event is after compaction, keep it.
        result.add(event);
        e--;
      } else {
        // Otherwise the event is before the compaction, let's move to the next compaction event;
        result.add(createCompactionEvent(compactionEvents.get(c), agentName));
        c--;
      }
    }
    // Flush any remaining compactions.
    while (c >= 0) {
      result.add(createCompactionEvent(compactionEvents.get(c), agentName));
      c--;
    }
    // Flush any remaining regular events.
    while (e >= 0) {
      result.add(regularEvents.get(e));
      e--;
    }
    return Lists.reverse(result);
  }

  private static List<Event> removeOverlappingCompactions(List<Event> events) {
    List<Event> result = new ArrayList<>();
    // Iterate backwards to prioritize later compactions
    for (int i = events.size() - 1; i >= 0; i--) {
      Event current = events.get(i);
      EventCompaction c = current.actions().compaction().get();

      // Check if this compaction is covered by the last compaction we've already kept.
      boolean covered = false;
      if (!result.isEmpty()) {
        EventCompaction lastKept = Iterables.getLast(result).actions().compaction().get();
        covered =
            c.startTimestamp() >= lastKept.startTimestamp()
                && c.endTimestamp() <= lastKept.endTimestamp();
      }

      if (!covered) {
        result.add(current);
      }
    }
    return Lists.reverse(result);
  }

  private static Event createCompactionEvent(Event event, String agentName) {
    EventCompaction compaction = event.actions().compaction().get();
    return event.toBuilder()
        .timestamp(compaction.endTimestamp())
        // Authored by this agent, so convertForeignEvent leaves the summary alone.
        .author(agentName.isEmpty() ? "model" : agentName)
        .content(compaction.compactedContent())
        .build();
  }

  /**
   * Re-injects function-call events from {@code sourceEvents} when compaction summarized away a
   * call whose response arrived later. Restores the full call event to preserve first-part thought
   * signatures on parallel calls, along with the latest compacted response for each sibling call so
   * siblings do not appear pending. Resolves call ownership in {@code sourceEvents} order by event
   * identity so reused call IDs bind to the latest preceding call.
   */
  private static List<Event> recoverCompactedFunctionCalls(
      List<Event> events, List<Event> sourceEvents) {
    Set<Event> presentEvents = Collections.newSetFromMap(new IdentityHashMap<>());
    presentEvents.addAll(events);

    Map<String, Event> latestCallById = new HashMap<>();
    IdentityHashMap<Event, List<Event>> callsByResponse = new IdentityHashMap<>();
    IdentityHashMap<Event, Map<String, Event>> responseByCall = new IdentityHashMap<>();
    IdentityHashMap<Event, Set<String>> survivingResponses = new IdentityHashMap<>();

    for (Event event : sourceEvents) {
      for (FunctionCall functionCall : event.functionCalls()) {
        functionCall
            .id()
            .filter(id -> !id.isEmpty())
            .ifPresent(id -> latestCallById.put(id, event));
      }
      for (FunctionResponse functionResponse : event.functionResponses()) {
        String responseId = functionResponse.id().orElse("");
        Event callEvent = latestCallById.get(responseId);
        if (callEvent == null) {
          continue;
        }
        callsByResponse.computeIfAbsent(event, k -> new ArrayList<>()).add(callEvent);
        if (presentEvents.contains(event)) {
          survivingResponses.computeIfAbsent(callEvent, k -> new HashSet<>()).add(responseId);
        }
        // Keep the latest update for this call; equal timestamps retain source order.
        Map<String, Event> responsesForCall =
            responseByCall.computeIfAbsent(callEvent, k -> new HashMap<>());
        Event existing = responsesForCall.get(responseId);
        if (existing == null || event.timestamp() >= existing.timestamp()) {
          responsesForCall.put(responseId, event);
        }
      }
    }

    List<Event> result = new ArrayList<>();
    Set<Event> reinjectedCalls = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Event event : events) {
      for (Event callEvent : callsByResponse.getOrDefault(event, ImmutableList.of())) {
        if (presentEvents.contains(callEvent) || !reinjectedCalls.add(callEvent)) {
          continue;
        }
        result.add(callEvent);
        Set<String> survivingForCall =
            survivingResponses.getOrDefault(callEvent, ImmutableSet.of());
        Map<String, Event> responsesForCall =
            responseByCall.getOrDefault(callEvent, ImmutableMap.of());
        Set<Event> siblingResponsesToAdd = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FunctionCall siblingCall : callEvent.functionCalls()) {
          String siblingId = siblingCall.id().orElse("");
          if (!siblingId.isEmpty() && !survivingForCall.contains(siblingId)) {
            Event siblingResponse = responsesForCall.get(siblingId);
            if (siblingResponse != null) {
              siblingResponsesToAdd.add(siblingResponse);
            }
          }
        }
        for (Event sourceEvent : sourceEvents) {
          if (siblingResponsesToAdd.contains(sourceEvent)) {
            result.add(sourceEvent);
          }
        }
      }
      result.add(event);
    }
    return result;
  }

  /** Whether the event is a reply from another agent. */
  private static boolean isOtherAgentReply(String agentName, Event event) {
    return !agentName.isEmpty()
        && !event.author().equals(agentName)
        && !event.author().equals(Role.USER);
  }

  /**
   * Converts an {@code event} authored by another agent to a 'contextual-only' event.
   *
   * <p>Returns {@code null} when nothing but the preamble survives the conversion, so the caller
   * drops the event instead of sending a preamble with no context after it.
   *
   * <p>The relayed text is attacker-reachable: whoever talks to the other agent steers what it
   * says, and its tool results carry whatever the tool read. Each relayed text payload is therefore
   * fenced (see {@link Fencing}), and the leading part states that fenced content is data, so a
   * payload has to be believed rather than merely obeyed.
   */
  private static @Nullable Event convertForeignEvent(Event event) {
    if (event.content().isEmpty()
        || event.content().get().parts().isEmpty()
        || event.content().get().parts().get().isEmpty()) {
      return event;
    }

    List<Part> parts = new ArrayList<>();
    parts.add(Part.fromText(Fencing.OTHER_AGENT_CONTEXT_PREAMBLE));

    String originalAuthor = event.author();

    for (Part part : event.content().get().parts().get()) {
      // Thoughts belong to the agent that produced them and are never narrated, whatever else the
      // part carries. ADK Python and ADK Kotlin both skip them before the branches below.
      if (part.thought().orElse(false)) {
        continue;
      }
      // Blank text is not narrated: such a part is a signature carrier, and a bare "said:" would
      // both pollute the prompt and keep the event alive on nothing.
      if (part.text().map(text -> !text.isBlank()).orElse(false)) {
        parts.add(
            Part.fromText(
                String.format(
                    "[%s] said:\n%s", originalAuthor, Fencing.quoteUntrusted(part.text().get()))));
      } else if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        // The tool name is model-chosen too, so it is elided but left unfenced: it reads as
        // part of the sentence and a fence there would obscure which tool ran.
        parts.add(
            Part.fromText(
                String.format(
                    "[%s] called tool `%s` with parameters:\n%s",
                    originalAuthor,
                    Fencing.elideQuoteMarkers(functionCall.name().orElse("unknown_tool")),
                    Fencing.quoteUntrusted(
                        functionCall.args().map(Contents::convertMapToJson).orElse("{}")))));
      } else if (part.functionResponse().isPresent()) {
        FunctionResponse functionResponse = part.functionResponse().get();
        parts.add(
            Part.fromText(
                String.format(
                    "[%s] `%s` tool returned result:\n%s",
                    originalAuthor,
                    Fencing.elideQuoteMarkers(functionResponse.name().orElse("unknown_tool")),
                    Fencing.quoteUntrusted(
                        functionResponse
                            .response()
                            .map(Contents::convertMapToJson)
                            .orElse("{}")))));
      } else if (part.inlineData().isPresent()
          || part.fileData().isPresent()
          || part.executableCode().isPresent()
          || part.codeExecutionResult().isPresent()) {
        parts.add(part);
      }
      // Anything else - a bare signature, a server-side call - belongs to the model instance that
      // produced it, so claiming it for another agent would be wrong.
    }

    if (parts.size() == 1) {
      return null;
    }

    Content content = Content.builder().role(Role.USER).parts(parts).build();
    return event.toBuilder().author(Role.USER).content(content).build();
  }

  private static String convertMapToJson(Map<String, Object> struct) {
    try {
      return JsonBaseModel.getMapper().writeValueAsString(struct);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize the object to JSON.", e);
    }
  }

  private static boolean isEventBelongsToBranch(@Nullable String invocationBranch, Event event) {
    @Nullable String eventBranch = event.branch().orElse(null);

    // Branches are dot-joined agent names, so a raw prefix match would make "root.agent_10" belong
    // to the branch "root.agent_1". Require either an exact match, or a prefix that ends on a
    // segment boundary.
    return Strings.isNullOrEmpty(invocationBranch)
        || Strings.isNullOrEmpty(eventBranch)
        || invocationBranch.equals(eventBranch)
        || invocationBranch.startsWith(eventBranch + ".");
  }

  /**
   * Rearranges the events for the latest function response. If the latest function response is for
   * an async function call, all events between the initial function call and the latest function
   * response will be removed.
   *
   * @param events The list of events.
   * @return A new list of events with the appropriate rearrangement.
   */
  private static List<Event> rearrangeEventsForLatestFunctionResponse(List<Event> events) {
    if (events.size() < 2) {
      // No need to process, since there is no function_call.
      return events;
    }

    if (events.isEmpty() || Iterables.getLast(events).functionResponses().isEmpty()) {
      // No need to process if the list is empty or the last event is not a function response
      return events;
    }

    Event latestEvent = Iterables.getLast(events);
    // Extract function response IDs from the latest event
    Set<String> functionResponseIds = new HashSet<>();
    latestEvent
        .content()
        .flatMap(Content::parts)
        .ifPresent(
            parts -> {
              for (Part part : parts) {
                part.functionResponse()
                    .flatMap(FunctionResponse::id)
                    .ifPresent(functionResponseIds::add);
              }
            });

    if (functionResponseIds.isEmpty()) {
      return events;
    }

    // Check if the second to last event contains the corresponding function call
    if (events.size() >= 2) {
      Event penultimateEvent = events.get(events.size() - 2);
      boolean matchFound =
          penultimateEvent
              .content()
              .flatMap(Content::parts)
              .map(
                  parts -> {
                    for (Part part : parts) {
                      if (part.functionCall()
                          .flatMap(FunctionCall::id)
                          .map(functionResponseIds::contains)
                          .orElse(false)) {
                        return true; // Found a matching function call ID
                      }
                    }
                    return false;
                  })
              .orElse(false);
      if (matchFound) {
        // The latest function response is already matched with the immediately preceding event
        return events;
      }
    }

    // Look for the corresponding function call event by iterating backwards
    int functionCallEventIndex = -1;
    for (int i = events.size() - 3; i >= 0; i--) { // Start from third-to-last
      Event event = events.get(i);
      Optional<List<Part>> partsOptional = event.content().flatMap(Content::parts);
      if (partsOptional.isPresent()) {
        List<Part> parts = partsOptional.get();
        for (Part part : parts) {
          Optional<String> callIdOpt = part.functionCall().flatMap(FunctionCall::id);
          if (callIdOpt.isPresent() && functionResponseIds.contains(callIdOpt.get())) {
            functionCallEventIndex = i;
            // Add all function call IDs from this event to the set
            parts.forEach(
                p ->
                    p.functionCall().flatMap(FunctionCall::id).ifPresent(functionResponseIds::add));
            break; // Found the matching event
          }
        }
      }
      if (functionCallEventIndex != -1) {
        break; // Exit outer loop once found
      }
    }

    if (functionCallEventIndex == -1) {
      if (!functionResponseIds.isEmpty()) {
        throw new IllegalStateException(
            "No function call event found for function response IDs: " + functionResponseIds);
      } else {
        return events; // No IDs to match, no rearrangement based on this logic.
      }
    }

    List<Event> resultEvents = new ArrayList<>(events.subList(0, functionCallEventIndex + 1));

    // Collect all function response events between the call and the latest response
    List<Event> functionResponseEventsToMerge = new ArrayList<>();
    for (int i = functionCallEventIndex + 1; i < events.size() - 1; i++) {
      Event intermediateEvent = events.get(i);
      boolean hasMatchingResponse =
          intermediateEvent
              .content()
              .flatMap(Content::parts)
              .map(
                  parts -> {
                    for (Part part : parts) {
                      if (part.functionResponse()
                          .flatMap(FunctionResponse::id)
                          .map(functionResponseIds::contains)
                          .orElse(false)) {
                        return true;
                      }
                    }
                    return false;
                  })
              .orElse(false);
      if (hasMatchingResponse) {
        functionResponseEventsToMerge.add(intermediateEvent);
      }
    }
    functionResponseEventsToMerge.add(latestEvent);

    if (!functionResponseEventsToMerge.isEmpty()) {
      resultEvents.add(mergeFunctionResponseEvents(functionResponseEventsToMerge));
    }

    return resultEvents;
  }

  private static List<Event> rearrangeEventsForAsyncFunctionResponsesInHistory(
      List<Event> events, boolean groupFunctionResponses) {
    // Pair each response with the latest preceding call of that ID, keeping the last update.
    Map<String, Integer> latestCallIndexById = new HashMap<>();
    Map<Integer, Map<String, Integer>> responseEventIndexByCall = new HashMap<>();
    for (int i = 0; i < events.size(); i++) {
      final int eventIndex = i;
      Event event = events.get(eventIndex);
      for (FunctionCall call : event.functionCalls()) {
        call.id().ifPresent(id -> latestCallIndexById.put(id, eventIndex));
      }
      for (FunctionResponse response : event.functionResponses()) {
        response
            .id()
            .map(latestCallIndexById::get)
            .ifPresent(
                owningCallIndex ->
                    responseEventIndexByCall
                        .computeIfAbsent(owningCallIndex, k -> new HashMap<>())
                        .put(response.id().get(), eventIndex));
      }
    }

    List<Event> resultEvents = new ArrayList<>();
    // Keep track of response events already added to avoid duplicates when merging
    Set<Integer> processedResponseIndices = new HashSet<>();
    // Buffers function responses so they can be emitted after their function calls (see below).
    List<Event> responseEventsBuffer = new ArrayList<>();

    // When opted in (RunConfig.groupFunctionResponsesInHistory), all function calls are grouped
    // first and only then all function responses (FC1, FC2, FR1, FR2); otherwise responses stay
    // paired with their call (FC1, FR1, FC2, FR2). Some model checkpoints require the grouped form.
    boolean shouldBufferResponseEvents = groupFunctionResponses;

    for (int i = 0; i < events.size(); i++) {
      Event event = events.get(i);

      if (!event.functionResponses().isEmpty()) {
        continue;
      }

      Optional<List<Part>> partsOptional = event.content().flatMap(Content::parts);
      boolean hasFunctionCalls =
          partsOptional
              .map(parts -> parts.stream().anyMatch(p -> p.functionCall().isPresent()))
              .orElse(false);

      if (hasFunctionCalls) {
        Map<String, Integer> responsesForCall =
            responseEventIndexByCall.getOrDefault(i, ImmutableMap.of());
        Set<Integer> responseEventIndices = new HashSet<>();
        // Iterate through parts again to get function call IDs
        partsOptional
            .get()
            .forEach(
                part ->
                    part.functionCall()
                        .ifPresent(
                            call ->
                                call.id()
                                    .ifPresent(
                                        functionCallId -> {
                                          Integer respIdx = responsesForCall.get(functionCallId);
                                          if (respIdx != null) {
                                            responseEventIndices.add(respIdx);
                                          }
                                        })));

        resultEvents.add(event); // Add the function call event

        if (!responseEventIndices.isEmpty()) {
          List<Event> responseEventsToAdd = new ArrayList<>();
          List<Integer> sortedIndices = new ArrayList<>(responseEventIndices);
          Collections.sort(sortedIndices); // Process in chronological order

          for (int index : sortedIndices) {
            if (processedResponseIndices.add(index)) { // Add index and check if it was newly added
              responseEventsBuffer.add(events.get(index));
              responseEventsToAdd.add(events.get(index));
            }
          }

          // When grouping is enabled the responses stay buffered and are flushed together after the
          // run of function calls; otherwise they are emitted immediately, paired with their call.
          if (!shouldBufferResponseEvents) {
            if (responseEventsToAdd.size() == 1) {
              resultEvents.add(responseEventsToAdd.get(0));
            } else if (responseEventsToAdd.size() > 1) {
              resultEvents.add(mergeFunctionResponseEvents(responseEventsToAdd));
            }
          }
        }
      } else {
        // Flush buffered function responses before the next non-function-call event so that the
        // grouped calls are immediately followed by their grouped responses.
        if (shouldBufferResponseEvents) {
          flushResponseEventsBuffer(responseEventsBuffer, resultEvents);
        }
        resultEvents.add(event);
      }
    }

    // Flush any function responses buffered after the last function call.
    if (shouldBufferResponseEvents) {
      flushResponseEventsBuffer(responseEventsBuffer, resultEvents);
    }

    return resultEvents;
  }

  /**
   * Flushes buffered function response events into {@code resultEvents}, merging them into a single
   * event when there is more than one. Used to group function responses after their function calls
   * for models that require it (Gemini 3).
   */
  private static void flushResponseEventsBuffer(
      List<Event> responseEventsBuffer, List<Event> resultEvents) {
    if (responseEventsBuffer.isEmpty()) {
      return;
    }
    if (responseEventsBuffer.size() == 1) {
      resultEvents.add(responseEventsBuffer.get(0));
    } else {
      resultEvents.add(mergeFunctionResponseEvents(responseEventsBuffer));
    }
    responseEventsBuffer.clear();
  }

  /**
   * Merges a list of function response events into one event.
   *
   * <p>The key goal is to ensure: 1. functionCall and functionResponse are always of the same
   * number. 2. The functionCall and functionResponse are consecutively in the content.
   *
   * @param functionResponseEvents A list of function response events. NOTE: functionResponseEvents
   *     must fulfill these requirements: 1. The list is in increasing order of timestamp; 2. the
   *     first event is the initial function response event; 3. all later events should contain at
   *     least one function response part that related to the function call event. Caveat: This
   *     implementation doesn't support when a parallel function call event contains async function
   *     call of the same name.
   * @return A merged event, that is 1. All later function_response will replace function response
   *     part in the initial function response event. 2. All non-function response parts will be
   *     appended to the part list of the initial function response event.
   */
  private static Event mergeFunctionResponseEvents(List<Event> functionResponseEvents) {
    checkArgument(
        !functionResponseEvents.isEmpty(), "At least one functionResponse event is required.");
    if (functionResponseEvents.size() == 1) {
      return functionResponseEvents.get(0);
    }

    Event baseEvent = functionResponseEvents.get(0);
    Content baseContent =
        baseEvent
            .content()
            .orElseThrow(() -> new IllegalArgumentException("Base event must have content."));
    List<Part> baseParts =
        baseContent
            .parts()
            .orElseThrow(() -> new IllegalArgumentException("Base event content must have parts."));

    checkArgument(
        !baseParts.isEmpty(),
        "There should be at least one functionResponse part in the base event.");
    List<Part> partsInMergedEvent = new ArrayList<>(baseParts);

    Map<String, Integer> partIndicesInMergedEvent = new HashMap<>();
    for (int i = 0; i < partsInMergedEvent.size(); i++) {
      final int index = i;
      Part part = partsInMergedEvent.get(i);
      if (part.functionResponse().isPresent()) {
        part.functionResponse()
            .get()
            .id()
            .ifPresent(functionCallId -> partIndicesInMergedEvent.put(functionCallId, index));
      }
    }

    for (Event event : functionResponseEvents.subList(1, functionResponseEvents.size())) {
      if (!hasContentWithNonEmptyParts(event)) {
        continue;
      }

      for (Part part : event.content().get().parts().get()) {
        if (part.functionResponse().isPresent()) {
          Optional<String> functionCallIdOpt = part.functionResponse().get().id();
          if (functionCallIdOpt.isPresent()) {
            String functionCallId = functionCallIdOpt.get();
            if (partIndicesInMergedEvent.containsKey(functionCallId)) {
              partsInMergedEvent.set(partIndicesInMergedEvent.get(functionCallId), part);
            } else {
              partsInMergedEvent.add(part);
              partIndicesInMergedEvent.put(functionCallId, partsInMergedEvent.size() - 1);
            }
          } else {
            partsInMergedEvent.add(part);
          }
        } else {
          partsInMergedEvent.add(part);
        }
      }
    }

    return baseEvent.toBuilder()
        .content(Content.builder().role(baseContent.role().get()).parts(partsInMergedEvent).build())
        .build();
  }

  private static boolean hasContentWithNonEmptyParts(Event event) {
    return event
        .content() // Optional<Content>
        .flatMap(Content::parts) // Optional<List<Part>>
        .map(list -> !list.isEmpty()) // Optional<Boolean>
        .orElse(false);
  }

  /** Checks if the event is a request confirmation event. */
  private static boolean isRequestConfirmationEvent(Event event) {
    return event.content().flatMap(Content::parts).stream()
        .flatMap(List::stream)
        // return event.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
        .anyMatch(
            part ->
                part.functionCall()
                        .flatMap(FunctionCall::name)
                        .map(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME::equals)
                        .orElse(false)
                    || part.functionResponse()
                        .flatMap(FunctionResponse::name)
                        .map(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME::equals)
                        .orElse(false));
  }
}
