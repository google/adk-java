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

import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.onlyRequest;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.requestResult;
import static com.google.adk.workflow.WorkflowResumeTesting.requests;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.events.Event;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Runs a time-off workflow through a {@code Runner}: each requested day is checked in parallel, a
 * day short on cover asks the team lead, and a manager decides on the days the checks allow. Each
 * ask is a {@link FunctionNodes#requestInput} call in a body, and its answer is the node's output.
 */
@RunWith(JUnit4.class)
public final class TimeOffWorkflowTest {

  /** The day the team is short on cover, which the team lead must approve. */
  private static final String SHORT_DAY = "2026-12-24";

  private static final String MANAGER_APPROVAL_ID = "manager_approval";

  record TimeOffRequest(String employee, ImmutableList<String> days) {}

  record DayRequest(String employee, String day) {}

  record DayCheck(String day, boolean approved) {}

  record TimeOffDecision(boolean approved, int days) {}

  @Test
  public void timeOffWorkflow_dayItemAndManagerAnswered_finishesWithTheManagersDecision() {
    // The short day pauses its parallel item, then the manager decides on the rest.
    WorkflowSession session =
        new WorkflowSession(
            timeOffWorkflow(
                new TimeOffRequest(
                    "ana", ImmutableList.of("2026-12-23", SHORT_DAY, "2026-12-28"))));

    ImmutableList<Event> paused = session.turn(go());
    ImmutableList<Event> checked =
        session.turn(
            requestResult(
                "cover_" + SHORT_DAY,
                ImmutableMap.of("day", SHORT_DAY, "approved", false, "by", "lead")));
    ImmutableList<Event> decided =
        session.turn(
            requestResult(
                MANAGER_APPROVAL_ID,
                ImmutableMap.of("approved", true, "days", 2L, "note", "enjoy")));

    FunctionCall coverAsk = onlyRequest(paused);
    assertThat(coverAsk.id()).hasValue("cover_" + SHORT_DAY);
    assertThat(coverAsk.args().orElseThrow())
        .containsEntry(
            RequestInput.PAYLOAD_KEY, ImmutableMap.of("employee", "ana", "day", SHORT_DAY));
    // As in ADK Kotlin, an answer is not read as the output type, so it keeps keys DayCheck lacks.
    assertThat(outputEventAt(checked, "wf@1/check_day@1").output())
        .hasValue(
            ImmutableList.of(
                ImmutableMap.of("day", "2026-12-23", "approved", true),
                ImmutableMap.of("day", SHORT_DAY, "approved", false, "by", "lead"),
                ImmutableMap.of("day", "2026-12-28", "approved", true)));
    FunctionCall managerAsk = onlyRequest(checked);
    assertThat(managerAsk.id()).hasValue(MANAGER_APPROVAL_ID);
    assertThat(managerAsk.args().orElseThrow())
        .containsEntry(RequestInput.PAYLOAD_KEY, ImmutableList.of("2026-12-23", "2026-12-28"));
    assertThat(outputEventAt(decided, "wf@1/manager_approval@1").output())
        .hasValue(ImmutableMap.of("approved", true, "days", 2L, "note", "enjoy"));
    assertThat(requests(decided)).isEmpty();
  }

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
        FunctionNodes.of(
            "check_day",
            DayRequest.class,
            DayCheck.class,
            (ctx, dayRequest) ->
                dayRequest.day().equals(SHORT_DAY)
                    ? FunctionNodes.requestInput(
                        "cover_" + dayRequest.day(),
                        "The team is short on cover that day. Approve it?",
                        dayRequest)
                    : new DayCheck(dayRequest.day(), true));
    FunctionNode<List<DayCheck>, TimeOffDecision> managerApproval =
        FunctionNodes.of(
            "manager_approval",
            new TypeReference<List<DayCheck>>() {},
            TimeOffDecision.class,
            (ctx, checks) ->
                FunctionNodes.requestInput(
                    MANAGER_APPROVAL_ID,
                    "Please review these days off.",
                    checks.stream()
                        .filter(DayCheck::approved)
                        .map(DayCheck::day)
                        .collect(toImmutableList())));
    return Workflow.builder()
        .name("wf")
        .edges(g ->
            g.fromStart()
                .chain(processRequest, ParallelWorkers.parallelNode(checkDay), managerApproval))
        .build();
  }
}
