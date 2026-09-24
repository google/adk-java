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

package com.google.adk.flows.llmflows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides how a resumable flow's next step continues: carry on to the model, stop because a call is
 * still unanswered, or run calls a previous run never executed.
 *
 * <p>Ports Python ADK's resume decision. Java has no per-call branch run ids, so the sub-branch
 * case Python recognizes (a HITL answer returning against a branch the call opened) has no
 * counterpart here and is treated as absent.
 */
final class StepResume {

  /** What the flow should do with the events it resumed from. */
  enum Action {
    /** Nothing outstanding; proceed to the model call. */
    CONTINUE,
    /** A call is still unanswered; stop without emitting anything. */
    PAUSE,
    /** A call was never executed; run the calls on {@link Decision#event}. */
    REPLAY_CALLS
  }

  /** The action to take, and the event it applies to. */
  static final class Decision {
    final Action action;
    private final @Nullable Event event;

    private Decision(Action action, @Nullable Event event) {
      this.action = action;
      this.event = event;
    }

    /** The event whose calls to run; only a {@code REPLAY_CALLS} decision carries one. */
    Event replayEvent() {
      if (event == null) {
        throw new IllegalStateException(action + " decision carries no event to replay");
      }
      return event;
    }
  }

  private static final Decision CONTINUE = new Decision(Action.CONTINUE, null);
  private static final Decision PAUSE = new Decision(Action.PAUSE, null);

  /**
   * Decides how the next step resumes. The branch's last event decides the case: content means the
   * normal flow carries on, while a call that was never executed has to run first.
   */
  static Decision decide(InvocationContext context, Map<String, BaseTool> tools) {
    if (!context.isResumable()) {
      return CONTINUE;
    }
    ImmutableList<Event> events =
        context.events(/* currentInvocation= */ true, /* currentBranch= */ true);
    if (events.isEmpty()) {
      return CONTINUE;
    }
    if (events.size() > 1) {
      Decision decision = decideFromBranch(context, events, tools);
      if (decision.action != Action.CONTINUE) {
        return decision;
      }
    }
    // Calls on the last event are unanswered by construction -- nothing follows them to answer.
    Event last = events.get(events.size() - 1);
    if (!last.partial().orElse(false)
        && !last.functionCalls().isEmpty()
        // A caller-supplied event would otherwise run a tool with no model turn behind it.
        && context.agent().name().equals(last.author())) {
      return new Decision(Action.REPLAY_CALLS, last);
    }
    return CONTINUE;
  }

  /** The multi-event core: pause on an unanswered call, else replay an unexecuted one. */
  private static Decision decideFromBranch(
      InvocationContext context, List<Event> events, Map<String, BaseTool> tools) {
    Event last = events.get(events.size() - 1);
    boolean pausedByLast = context.shouldPauseInvocation(last);
    if (!pausedByLast && pauseLeftCallsUnanswered(context, events)) {
      return PAUSE;
    }

    int callIdx = findTargetCallEventIndex(context, events, tools);
    if (callIdx < 0) {
      return pausedByLast ? PAUSE : CONTINUE;
    }
    Event callEvent = events.get(callIdx);
    Set<String> callNames = new HashSet<>();
    Set<String> callIds = new HashSet<>();
    // An id-less call can never be answered, so it pauses; Python keeps a None in its id set.
    boolean hasIdlessCall = false;
    for (FunctionCall call : callEvent.functionCalls()) {
      call.name().ifPresent(callNames::add);
      call.id().ifPresent(callIds::add);
      hasIdlessCall |= call.id().isEmpty();
    }
    Set<String> longRunningIds = new HashSet<>();
    for (int i = callIdx; i < events.size(); i++) {
      longRunningIds.addAll(events.get(i).longRunningToolIds().orElse(ImmutableSet.of()));
    }
    callIds.addAll(longRunningIds);

    Set<String> answeredIds = new HashSet<>();
    for (int i = callIdx + 1; i < events.size(); i++) {
      for (FunctionResponse response : events.get(i).functionResponses()) {
        response.id().ifPresent(answeredIds::add);
      }
    }
    ImmutableList<FunctionResponse> answers = answerEvent(events, callIdx, callIds, callNames);

    boolean lroUnanswered = !longRunningIds.isEmpty() && disjoint(longRunningIds, answeredIds);
    boolean callUnanswered =
        (!callIds.isEmpty() || hasIdlessCall)
            && disjoint(callIds, answeredIds)
            && answers.stream()
                .noneMatch(response -> response.name().map(callNames::contains).orElse(false));
    if (lroUnanswered || callUnanswered) {
      return PAUSE;
    }
    if (needsCallReplay(callNames, answers)) {
      return new Decision(Action.REPLAY_CALLS, callEvent);
    }
    return pausedByLast ? PAUSE : CONTINUE;
  }

  /**
   * Whether a pause earlier in {@code events} is still waiting. Every event before the last counts:
   * a long-running call followed by several responses leaves the pausing call further back than a
   * two-event window can see.
   */
  private static boolean pauseLeftCallsUnanswered(InvocationContext context, List<Event> events) {
    Set<String> awaited = new HashSet<>();
    for (int i = 0; i < events.size() - 1; i++) {
      Event event = events.get(i);
      if (!context.shouldPauseInvocation(event)) {
        continue;
      }
      // Union, as in Python: every call on a pausing event plus its long-running ids. A different
      // rule from shouldPauseInvocation's, which intersects the two.
      for (FunctionCall call : event.functionCalls()) {
        call.id().ifPresent(awaited::add);
      }
      awaited.addAll(event.longRunningToolIds().orElse(ImmutableSet.of()));
    }
    if (awaited.isEmpty()) {
      return false;
    }
    Set<String> answered = new HashSet<>();
    for (Event event : events) {
      for (FunctionResponse response : event.functionResponses()) {
        response.id().ifPresent(answered::add);
      }
    }
    // A partially answered pause keeps waiting.
    return !answered.containsAll(awaited);
  }

  /**
   * Index of the most recent event before the last that calls a tool this flow owns and the current
   * agent authored, or -1 when there is none.
   */
  private static int findTargetCallEventIndex(
      InvocationContext context, List<Event> events, Map<String, BaseTool> tools) {
    String agentName = context.agent().name();
    for (int i = events.size() - 2; i >= 0; i--) {
      Event event = events.get(i);
      if (!agentName.equals(event.author())) {
        continue;
      }
      boolean callsOwnedTool =
          event.functionCalls().stream()
              .anyMatch(call -> call.name().map(tools::containsKey).orElse(false));
      if (callsOwnedTool) {
        return i;
      }
    }
    return -1;
  }

  /** The responses of the event answering the call, or of the last event when none does. */
  private static ImmutableList<FunctionResponse> answerEvent(
      List<Event> events, int callIdx, Set<String> callIds, Set<String> callNames) {
    for (int i = events.size() - 1; i > callIdx; i--) {
      for (FunctionResponse response : events.get(i).functionResponses()) {
        boolean matchesId = response.id().map(callIds::contains).orElse(false);
        boolean matchesName =
            response.id().isEmpty() && response.name().map(callNames::contains).orElse(false);
        if (matchesId || matchesName) {
          return events.get(i).functionResponses();
        }
      }
    }
    return events.get(events.size() - 1).functionResponses();
  }

  /** Whether the calls still need running: nothing answered them, or something else did. */
  private static boolean needsCallReplay(Set<String> callNames, List<FunctionResponse> answers) {
    if (callNames.isEmpty()) {
      return false;
    }
    return answers.isEmpty()
        || answers.stream()
            .anyMatch(response -> !response.name().map(callNames::contains).orElse(false));
  }

  private static boolean disjoint(Set<String> left, Set<String> right) {
    return left.stream().noneMatch(right::contains);
  }

  private StepResume() {}
}
