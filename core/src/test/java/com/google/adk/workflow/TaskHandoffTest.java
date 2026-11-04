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

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests the handoffs that delegated tasks and the agents around them make. */
@RunWith(JUnit4.class)
public final class TaskHandoffTest {

  @Test
  public void delegatedTaskThatHandsOffToAChatHelper_letsItsDelegatorGoOn() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer_task")
            .description("Does outer.")
            .model(TestLlm.create(transfer("x1", "helper")))
            .mode(AgentMode.TASK)
            .subAgents(helper(helperModel))
            .build();
    TestLlm rootModel =
        TestLlm.create(
            call("t1", "outer_task", ImmutableMap.of("request", "go")),
            createTextLlmResponse("Root done."));

    ImmutableList<Event> events =
        new WorkflowSession(coordinator(rootModel, outer)).turn(userText("start"));

    // The helper gives no output, as a chat agent does, so the delegation answers empty.
    assertThat(textsBy(events, "helper")).containsExactly("Helper here.");
    assertThat(responseNames(events)).contains("outer_task");
    assertThat(lastText(events)).isEqualTo("Root done.");
  }

  @Test
  public void taskHandingOffToAHelperWhoseTaskAsks_endsTheTurnAtTheQuestion() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .description("Helps.")
            .model(
                TestLlm.create(
                    call("t2", "inner_task", ImmutableMap.of("request", "pick")),
                    createTextLlmResponse("ok")))
            .subAgents(task("inner_task", TestLlm.create(createTextLlmResponse("Which one?"))))
            .build();
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer_task")
            .description("Does outer.")
            .model(TestLlm.create(transfer("x1", "helper")))
            .mode(AgentMode.TASK)
            .subAgents(helper)
            .build();
    TestLlm rootModel =
        TestLlm.create(
            call("t1", "outer_task", ImmutableMap.of("request", "go")),
            createTextLlmResponse("Root done."));

    ImmutableList<Event> events =
        new WorkflowSession(coordinator(rootModel, outer)).turn(userText("start"));

    assertThat(lastText(events)).isEqualTo("Which one?");
    assertThat(rootModel.getRequests()).hasSize(1);
  }

  @Test
  public void replyToTheHelpersTask_letsTheDelegatorGoOn() {
    assertReplyToTheHelpersTaskLetsTheDelegatorGoOn(/* resumable= */ true);
  }

  @Test
  public void replyToTheHelpersTaskWithoutResumability_letsTheDelegatorGoOn() {
    assertReplyToTheHelpersTaskLetsTheDelegatorGoOn(/* resumable= */ false);
  }

  private static void assertReplyToTheHelpersTaskLetsTheDelegatorGoOn(boolean resumable) {
    TestLlm innerModel =
        TestLlm.create(
            createTextLlmResponse("Which one?"),
            createFunctionCallLlmResponse(
                "f1", "finish_task", ImmutableMap.of("result", "Picked blue.")));
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .description("Helps.")
            .model(
                TestLlm.create(
                    call("t2", "inner_task", ImmutableMap.of("request", "pick")),
                    createTextLlmResponse("Helper done.")))
            .subAgents(task("inner_task", innerModel))
            .build();
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer_task")
            .description("Does outer.")
            .model(TestLlm.create(transfer("x1", "helper")))
            .mode(AgentMode.TASK)
            .subAgents(helper)
            .build();
    TestLlm rootModel =
        TestLlm.create(
            call("t1", "outer_task", ImmutableMap.of("request", "go")),
            createTextLlmResponse("Root done."));
    WorkflowSession session = new WorkflowSession(coordinator(rootModel, outer), resumable);
    session.advance(userText("start"));

    ImmutableList<Event> reply = session.turn(userText("Blue"));

    // The inner task finishes and the helper answers, so the root's delegation answers too.
    assertThat(textsBy(reply, "helper")).contains("Helper done.");
    assertThat(lastText(reply)).isEqualTo("Root done.");
    assertThat(rootModel.getRequests()).hasSize(2);
  }

  private static LlmAgent coordinator(TestLlm model, BaseAgent... subAgents) {
    return LlmAgent.builder()
        .name("coordinator")
        .model(model)
        .mode(AgentMode.CHAT)
        .subAgents(subAgents)
        .build();
  }

  private static LlmAgent task(String name, TestLlm model) {
    return LlmAgent.builder()
        .name(name)
        .description("Does " + name + ".")
        .model(model)
        .mode(AgentMode.TASK)
        .build();
  }

  private static LlmAgent helper(TestLlm model) {
    return LlmAgent.builder().name("helper").description("Helps.").model(model).build();
  }

  private static LlmResponse call(String id, String name, Map<String, Object> args) {
    return createFunctionCallLlmResponse(id, name, args);
  }

  private static LlmResponse transfer(String id, String agentName) {
    return call(id, "transfer_to_agent", ImmutableMap.of("agent_name", agentName));
  }

  private static ImmutableList<String> responseNames(List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionResponses().stream())
        .flatMap(response -> response.name().stream())
        .collect(toImmutableList());
  }

  private static ImmutableList<Event> withText(List<Event> events) {
    return events.stream()
        .filter(event -> event.functionCalls().isEmpty() && event.functionResponses().isEmpty())
        .filter(event -> !event.stringifyContent().isEmpty())
        .collect(toImmutableList());
  }

  private static String lastText(List<Event> events) {
    ImmutableList<Event> texts = withText(events);
    return texts.isEmpty() ? "" : texts.get(texts.size() - 1).stringifyContent();
  }

  private static ImmutableList<String> textsBy(List<Event> events, String author) {
    return withText(events).stream()
        .filter(event -> event.author().equals(author))
        .map(Event::stringifyContent)
        .collect(toImmutableList());
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }
}
