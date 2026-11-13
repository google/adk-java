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

import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.events.Event;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Runs a time-off workflow through a {@code Runner}: each requested day is checked in parallel, a
 * day short on cover asks the team lead, and a manager decides on the days the checks allow. Each
 * ask is a {@link RequestInput} that a flowable body emits, and its answer is the node's output.
 */
@RunWith(JUnit4.class)
public final class TimeOffWorkflowTest {

  /** The day the team is short on cover, which the team lead must approve. */
  private static final String SHORT_DAY = "2026-12-24";

  private static final String MANAGER_APPROVAL_ID = "manager_approval";

  record TimeOffRequest(String employee, List<String> days) {}

  record DayRequest(String employee, String day) {}

  record DayCheck(String day, boolean approved) {}

  record TimeOffDecision(boolean approved, int days) {}

  /** Builds the workflow for {@code request}; a node stands in for an agent that extracts it. */
  private static Workflow timeOffWorkflow(TimeOffRequest request) {
    FunctionNode<Content, List<DayRequest>> processRequest =
        FunctionNodes.of(
            "process_request",
            Content.class,
            new TypeReference<List<DayRequest>>() {},
            (ctx, message) ->
                request.days().stream()
                    .map(day -> new DayRequest(request.employee(), day))
                    .collect(toImmutableList()));
    FunctionNode<DayRequest, DayCheck> checkDay =
        FunctionNodes.ofFlowable(
            "check_day",
            DayRequest.class,
            DayCheck.class,
            (ctx, dayRequest) ->
                dayRequest.day().equals(SHORT_DAY)
                    ? Flowable.just(
                        RequestInput.builder()
                            .interruptId("cover_" + dayRequest.day())
                            .message("The team is short on cover that day. Approve it?")
                            .payload(dayRequest)
                            .build())
                    : Flowable.just(new DayCheck(dayRequest.day(), true)));
    FunctionNode<List<DayCheck>, TimeOffDecision> managerApproval =
        FunctionNodes.ofFlowable(
            "manager_approval",
            new TypeReference<List<DayCheck>>() {},
            TimeOffDecision.class,
            (ctx, checks) ->
                Flowable.just(
                    RequestInput.builder()
                        .interruptId(MANAGER_APPROVAL_ID)
                        .message("Please review these days off.")
                        .payload(
                            checks.stream()
                                .filter(DayCheck::approved)
                                .map(DayCheck::day)
                                .collect(toImmutableList()))
                        .build()));
    return Workflow.builder()
        .name("wf")
        .edges(g ->
            g.fromStart()
                .chain(processRequest, ParallelWorkers.parallelNode(checkDay), managerApproval))
        .build();
  }

  @Test
  public void timeOffWorkflow_dayItemAndManagerAnswered_finishesWithTheTypedDecision() {
    // Arrange: the short day pauses its parallel item, then the manager decides on the rest.
    WorkflowSession session =
        new WorkflowSession(
            timeOffWorkflow(
                new TimeOffRequest(
                    "ana", ImmutableList.of("2026-12-23", SHORT_DAY, "2026-12-28"))));

    ImmutableList<Event> paused = session.turn(go());
    ImmutableList<Event> checked =
        session.turn(
            requestAnswer(
                "cover_" + SHORT_DAY,
                ImmutableMap.of(
                    "result", ImmutableMap.of("day", SHORT_DAY, "approved", false, "by", "lead"))));
    ImmutableList<Event> decided =
        session.turn(
            requestAnswer(
                MANAGER_APPROVAL_ID,
                ImmutableMap.of(
                    "result", ImmutableMap.of("approved", true, "days", 2L, "note", "enjoy"))));

    FunctionCall coverAsk = onlyRequest(paused);
    assertThat(coverAsk.id()).hasValue("cover_" + SHORT_DAY);
    assertThat(coverAsk.args().orElseThrow())
        .containsEntry(
            RequestInput.PAYLOAD_KEY, ImmutableMap.of("employee", "ana", "day", SHORT_DAY));
    // DayCheck drops the extra key from the item's answer.
    assertThat(outputEventAt(checked, "wf@1/check_day@1").output())
        .hasValue(
            ImmutableList.of(
                ImmutableMap.of("day", "2026-12-23", "approved", true),
                ImmutableMap.of("day", SHORT_DAY, "approved", false),
                ImmutableMap.of("day", "2026-12-28", "approved", true)));
    FunctionCall managerAsk = onlyRequest(checked);
    assertThat(managerAsk.id()).hasValue(MANAGER_APPROVAL_ID);
    assertThat(managerAsk.args().orElseThrow())
        .containsEntry(RequestInput.PAYLOAD_KEY, ImmutableList.of("2026-12-23", "2026-12-28"));
    // TimeOffDecision drops the extra key and writes the Long days as its int.
    assertThat(outputEventAt(decided, "wf@1/manager_approval@1").output())
        .hasValue(ImmutableMap.of("approved", true, "days", 2));
    assertThat(requests(decided)).isEmpty();
  }

  private static ImmutableList<FunctionCall> requests(List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionCalls().stream())
        .filter(call -> call.name().orElse("").equals(RequestInput.FUNCTION_CALL_NAME))
        .collect(toImmutableList());
  }

  private static FunctionCall onlyRequest(List<Event> events) {
    return requests(events).stream().collect(onlyElement());
  }

  private static Content requestAnswer(String interruptId, Map<String, ?> response) {
    return answer(interruptId, response, RequestInput.FUNCTION_CALL_NAME);
  }
}
