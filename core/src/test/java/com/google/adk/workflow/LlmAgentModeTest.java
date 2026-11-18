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

import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.modelText;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.testing.TestLlm;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LlmAgentModeTest {

  @Test
  public void chatAgentNode_continuesTheConversationNextTurnWithoutRunningItsSuccessor() {
    TestLlm model =
        TestLlm.create(
            createTextLlmResponse("Which city?"), createTextLlmResponse("Paris is lovely."));
    LlmAgent guide = LlmAgent.builder().name("guide").model(model).mode(AgentMode.CHAT).build();
    Node after = emitter("after", "AFTER");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, guide), new Edge(guide, after)));

    var firstTurn = session.turn(userText("Plan a trip"));
    var secondTurn = session.turn(userText("Paris"));

    assertThat(model.getLastRequest().contents())
        .containsExactly(userText("Plan a trip"), modelText("Which city?"), userText("Paris"))
        .inOrder();
    assertThat(pathsOf(firstTurn)).doesNotContain("wf@1/after@1");
    assertThat(pathsOf(secondTurn)).doesNotContain("wf@1/after@1");
  }

  @Test
  public void chatAgentInANestedWorkflow_failsTheRun() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);
    Node inner = workflow("inner", new Edge(START, guide));
    Node after = emitter("after", "AFTER");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, inner), new Edge(inner, after)));

    var failed = session.failingTurn(userText("Plan a trip"));

    assertThat(failed.error()).isInstanceOf(GraphValidationException.class);
    assertThat(failed.error()).hasMessageThat().contains("runs inside another workflow");
    assertThat(pathsOf(failed.events())).doesNotContain("wf@1/after@1");
  }

  @Test
  public void chatAgentInAWorkflowThatARootNodeRuns_stillRuns() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);
    Node inner = workflow("inner", new Edge(START, guide));
    Node root = dispatcher("root", (ctx, input) -> ctx.runNode(inner, input).toFlowable());

    var events = new WorkflowSession(root).turn(userText("Hi"));

    assertThat(pathsOf(events)).contains("root@1/inner@1/guide@1");
  }

  @Test
  public void finishedChatAgent_doesNotRunAgainWhenASiblingResumesFromAnInterrupt() {
    TestLlm model = TestLlm.create("Hello!", "Hello again!");
    LlmAgent guide = LlmAgent.builder().name("guide").model(model).mode(AgentMode.CHAT).build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, guide), new Edge(START, longRunningAsk("ask", "i1"))));
    session.advance(go());

    session.advance(answer("i1", ImmutableMap.of("ok", true)));

    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void chatAgentInATopLevelWorkflow_stillRuns() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);

    var events = new WorkflowSession(workflow(new Edge(START, guide))).turn(userText("Hi"));

    assertThat(pathsOf(events)).contains("wf@1/guide@1");
  }

  @Test
  public void chatAgentFollowingANodeOtherThanStart_failsValidation() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);
    Node source = emitter("source", "S");
    Edge first = new Edge(START, source);
    Edge second = new Edge(source, guide);

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> workflow(first, second));

    assertThat(e).hasMessageThat().contains("AgentMode.CHAT following node 'source'");
  }

  @Test
  public void agentWithoutModeInATreeWithModes_isAChatAgent() {
    LlmAgent helper = agent("helper", AgentMode.SINGLE_TURN);
    LlmAgent worker = LlmAgent.builder().name("worker").model(TestLlm.create("ok")).build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create("ok"))
            .subAgents(helper, worker)
            .build();

    assertThat(coordinator.waitForOutput()).isTrue();
    assertThat(worker.waitForOutput()).isTrue();
    assertThat(helper.waitForOutput()).isFalse();
  }

  @Test
  public void agentWithoutModeInATreeWithoutModes_keepsRunningAsBefore() {
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create("ok"))
            .subAgents(LlmAgent.builder().name("worker").model(TestLlm.create("ok")).build())
            .build();
    Node source = emitter("source", "S");

    Workflow unused = workflow(new Edge(START, source), new Edge(source, coordinator));

    assertThat(coordinator.waitForOutput()).isFalse();
  }

  @Test
  public void agentMadeAChatAgentAfterTheGraphWasBuilt_failsTheRun() {
    LlmAgent worker = LlmAgent.builder().name("worker").model(TestLlm.create("ok")).build();
    Node source = emitter("source", "S");
    Workflow graph = workflow(new Edge(START, source), new Edge(source, worker));
    LlmAgent unused =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create("ok"))
            .subAgents(worker, agent("helper", AgentMode.SINGLE_TURN))
            .build();

    Throwable error = new WorkflowSession(graph).failingTurn(go()).error();

    assertThat(error).isInstanceOf(GraphValidationException.class);
    assertThat(error).hasMessageThat().contains("AgentMode.CHAT following node 'source'");
    assertThat(error).hasMessageThat().contains("The agent has no mode");
  }

  private static LlmAgent agent(String name, AgentMode mode) {
    return LlmAgent.builder().name(name).model(TestLlm.create("ok")).mode(mode).build();
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }
}
