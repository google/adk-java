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

import static com.google.adk.testing.ResumabilityTestUtils.newSession;
import static com.google.adk.testing.ResumabilityTestUtils.reloadSession;
import static com.google.adk.testing.ResumabilityTestUtils.resumableRunner;
import static com.google.adk.testing.ResumabilityTestUtils.resumeById;
import static com.google.adk.testing.ResumabilityTestUtils.runTurn;
import static com.google.adk.testing.ResumabilityTestUtils.textAgent;
import static com.google.adk.testing.TestUtils.END_OF_AGENT;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.adk.testing.TestUtils.simplifyResumableEvents;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.agents.TransferRejectedException;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AgentTransferTest {
  public static Part createTransferCallPart(String agentName) {
    return Part.fromFunctionCall("transfer_to_agent", ImmutableMap.of("agent_name", agentName));
  }

  public static Part createTransferResponsePart() {
    return Part.fromFunctionResponse("transfer_to_agent", ImmutableMap.<String, Object>of());
  }

  // Helper tool for testing LoopAgent
  public static class ExitLoopTool extends BaseTool {
    public ExitLoopTool() {
      super("exit_loop", "Exits the current loop.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder()
              .name(name())
              .description(description())
              .parameters(Schema.builder().type("OBJECT").build()) // No parameters needed
              .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      toolContext.setActions(toolContext.actions().toBuilder().escalate(true).build());
      return Single.just(ImmutableMap.of());
    }
  }

  /** A tool that always fails, counting its calls. */
  private static class FailingTool extends BaseTool {
    final AtomicInteger calls = new AtomicInteger();

    FailingTool() {
      super("fail", "Always fails.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder()
              .name(name())
              .description(description())
              .parameters(Schema.builder().type("OBJECT").build())
              .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      calls.incrementAndGet();
      return Single.error(new IllegalStateException("tool failed"));
    }
  }

  // Order is load-bearing: this list is rendered into the model's transfer instruction.
  @Test
  public void transferTargets_ordersSubAgentsThenParentThenPeers() {
    LlmAgent child =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("child")
            .build();
    LlmAgent peerA =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("peer_a")
            .build();
    LlmAgent subject =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("subject")
            .subAgents(child)
            .build();
    LlmAgent peerB =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("peer_b")
            .build();
    LlmAgent unused =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("parent")
            .subAgents(peerA, subject, peerB)
            .build();

    assertThat(AgentTransfer.transferTargets(subject).stream().map(BaseAgent::name))
        .containsExactly("child", "parent", "peer_a", "peer_b")
        .inOrder();
  }

  @Test
  public void transferTargets_disallowedParentAndPeers_returnsOnlySubAgents() {
    LlmAgent child =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("child")
            .build();
    LlmAgent peer =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused"))).name("peer").build();
    LlmAgent subject =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("subject")
            .subAgents(child)
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    LlmAgent unused =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("parent")
            .subAgents(subject, peer)
            .build();

    assertThat(AgentTransfer.transferTargets(subject).stream().map(BaseAgent::name))
        .containsExactly("child");
  }

  @Test
  public void transferTargets_noLlmAgentParent_returnsOnlySubAgents() {
    LlmAgent child =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("child")
            .build();
    LlmAgent subject =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("unused")))
            .name("subject")
            .subAgents(child)
            .build();

    assertThat(AgentTransfer.transferTargets(subject).stream().map(BaseAgent::name))
        .containsExactly("child");
  }

  @Test
  public void runLive_transferToAgent_closesConnection() throws Exception {
    // Arrange
    Content transferCallContent = Content.fromParts(createTransferCallPart("sub_agent_1"));
    Content response1 = Content.fromParts(Part.fromText("response1"));

    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(transferCallContent)),
            Flowable.just(createLlmResponse(response1)));

    LlmAgent subAgent1 = createTestAgentBuilder(testLlm).name("sub_agent_1").build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    // Act
    TestSubscriber<Event> testSubscriber =
        runner
            .runLive(invocationContext.session(), liveRequestQueue, RunConfig.builder().build())
            .test();
    liveRequestQueue.content(Content.fromParts(Part.fromText("hi")));
    testSubscriber.await();

    // Assert
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1: response1")
        .inOrder();

    long closedConnectionsCount =
        testLlm.getLiveRequestHistory().stream().filter(LiveRequest::shouldClose).count();
    assertThat(closedConnectionsCount).isEqualTo(1);
  }

  @Test
  public void testAutoToAuto() {
    Content transferCallContent = Content.fromParts(createTransferCallPart("sub_agent_1"));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));

    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(transferCallContent)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(response2)));

    LlmAgent subAgent1 = createTestAgentBuilder(testLlm).name("sub_agent_1").build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    List<Event> actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1: response1")
        .inOrder();

    actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents)).containsExactly("sub_agent_1: response2");
  }

  @Test
  public void testAutoToSingle() {
    Content transferCallContent = Content.fromParts(createTransferCallPart("sub_agent_1"));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));

    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(transferCallContent)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(response2)));

    LlmAgent subAgent1 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    List<Event> actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1: response1")
        .inOrder();

    actualEvents = runRunner(runner, invocationContext);
    // Since sub_agent_1 is a SingleFlow, the next turn starts from the root agent.
    assertThat(simplifyEvents(actualEvents)).containsExactly("root_agent: response2");
  }

  @Test
  public void testAutoToAutoToSingle() {
    Content transferCall1 = Content.fromParts(createTransferCallPart("sub_agent_1"));
    Content transferCall2 = Content.fromParts(createTransferCallPart("sub_agent_1_1"));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));

    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(transferCall1)),
            Flowable.just(createLlmResponse(transferCall2)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(response2)));

    LlmAgent subAgent11 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_1")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    LlmAgent subAgent1 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1")
            .subAgents(ImmutableList.of(subAgent11))
            .build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    List<Event> actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1_1})",
            "sub_agent_1: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1_1: response1")
        .inOrder();

    actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents)).containsExactly("sub_agent_1: response2");
  }

  @Test
  public void testAutoToSequential() {
    Content transferCallContent = Content.fromParts(createTransferCallPart("sub_agent_1"));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));
    Content response3 = Content.fromParts(Part.fromText("response3"));

    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(transferCallContent)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(response2)),
            Flowable.just(createLlmResponse(response3)));

    LlmAgent subAgent11 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_1")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    LlmAgent subAgent12 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_2")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    SequentialAgent subAgent1 =
        SequentialAgent.builder()
            .name("sub_agent_1")
            .description("sequential agent")
            .subAgents(ImmutableList.of(subAgent11, subAgent12))
            .build();

    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    List<Event> actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1_1: response1",
            "sub_agent_1_2: response2")
        .inOrder();

    actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents)).containsExactly("root_agent: response3");
  }

  @Test
  public void testAutoToSequentialToAuto() {
    Content transferCall1 = Content.fromParts(createTransferCallPart("sub_agent_1"));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content transferCall2 = Content.fromParts(createTransferCallPart("sub_agent_1_2_1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));
    Content response3 = Content.fromParts(Part.fromText("response3"));
    Content response4 = Content.fromParts(Part.fromText("response4"));

    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(transferCall1)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(transferCall2)),
            Flowable.just(createLlmResponse(response2)),
            Flowable.just(createLlmResponse(response3)),
            Flowable.just(createLlmResponse(response4)));

    LlmAgent subAgent11 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_1")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    LlmAgent subAgent121 = createTestAgentBuilder(testLlm).name("sub_agent_1_2_1").build();
    LlmAgent subAgent12 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_2")
            .subAgents(ImmutableList.of(subAgent121))
            .build();
    LlmAgent subAgent13 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_3")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    SequentialAgent subAgent1 =
        SequentialAgent.builder()
            .name("sub_agent_1")
            .subAgents(ImmutableList.of(subAgent11, subAgent12, subAgent13))
            .build();

    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    List<Event> actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1_1: response1",
            "sub_agent_1_2: FunctionCall(name=transfer_to_agent,"
                + " args={agent_name=sub_agent_1_2_1})",
            "sub_agent_1_2: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1_2_1: response2",
            "sub_agent_1_3: response3")
        .inOrder();
    actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents)).containsExactly("root_agent: response4");
  }

  @Test
  public void testAutoToLoop() {
    Content transferCallContent = Content.fromParts(createTransferCallPart("sub_agent_1"));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));
    Content response3 = Content.fromParts(Part.fromText("response3"));
    Content exitCallContent =
        Content.fromParts(Part.fromFunctionCall("exit_loop", ImmutableMap.of()));
    Content response4 = Content.fromParts(Part.fromText("response4"));
    Content response5 = Content.fromParts(Part.fromText("response5"));

    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(transferCallContent)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(response2)),
            Flowable.just(createLlmResponse(response3)),
            Flowable.just(createLlmResponse(exitCallContent)),
            Flowable.just(createLlmResponse(response4)),
            Flowable.just(createLlmResponse(response5)));

    LlmAgent subAgent11 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_1")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .build();
    LlmAgent subAgent12 =
        createTestAgentBuilder(testLlm)
            .name("sub_agent_1_2")
            .disallowTransferToParent(true)
            .disallowTransferToPeers(true)
            .tools(ImmutableList.of(new ExitLoopTool()))
            .build();
    LoopAgent subAgent1 =
        LoopAgent.builder()
            .name("sub_agent_1")
            .description("loop agent")
            .subAgents(ImmutableList.of(subAgent11, subAgent12))
            .build();

    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    List<Event> actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "sub_agent_1_1: response1",
            "sub_agent_1_2: response2",
            "sub_agent_1_1: response3",
            "sub_agent_1_2: FunctionCall(name=exit_loop, args={})",
            "sub_agent_1_2: FunctionResponse(name=exit_loop, response={})",
            "root_agent: response4")
        .inOrder();
    actualEvents = runRunner(runner, invocationContext);

    assertThat(simplifyEvents(actualEvents)).containsExactly("root_agent: response5");
  }

  // A grandchild is not a sub-agent, parent or peer, so it is not a transfer target.
  @Test
  public void runAsync_transferToGrandchild_failsAndGrandchildNeverRuns() {
    TestLlm grandchildLlm = TestLlm.create(createTextLlmResponse("grandchild ran"));
    Runner runner = new InMemoryRunner(rootTransferringToGrandchild(grandchildLlm), "test");
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'root' is not allowed to transfer to the requested agent.");
    assertThat(grandchildLlm.getRequests()).isEmpty();
  }

  @Test
  public void runAsync_resumable_transferToGrandchild_failsAndGrandchildNeverRuns() {
    TestLlm grandchildLlm = TestLlm.create(createTextLlmResponse("grandchild ran"));
    Runner runner = resumableRunner(rootTransferringToGrandchild(grandchildLlm));
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'root' is not allowed to transfer to the requested agent.");
    assertThat(grandchildLlm.getRequests()).isEmpty();
  }

  @Test
  public void runAsync_transferToSelf_failsAndTheAgentDoesNotRunAgain() {
    TestLlm rootLlm = TestLlm.create(transferTo("root"));
    Runner runner = new InMemoryRunner(root(rootLlm, textAgent("sub")), "test");
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error).hasMessageThat().isEqualTo("Agent 'root' cannot transfer to itself.");
    assertThat(rootLlm.getRequests()).hasSize(1);
  }

  @Test
  public void runAsync_resumable_transferToSelf_failsAndTheAgentDoesNotRunAgain() {
    TestLlm rootLlm = TestLlm.create(transferTo("root"));
    Runner runner = resumableRunner(root(rootLlm, textAgent("sub")));
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error).hasMessageThat().isEqualTo("Agent 'root' cannot transfer to itself.");
    assertThat(rootLlm.getRequests()).hasSize(1);
  }

  @Test
  public void runAsync_transferToParent_withDisallowTransferToParent_fails() {
    TestLlm rootLlm = TestLlm.create(transferTo("sub"));
    Runner runner = new InMemoryRunner(rootWithSubDisallowingParent(rootLlm), "test");
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'sub' is not allowed to transfer to the requested agent.");
    assertThat(rootLlm.getRequests()).hasSize(1);
  }

  @Test
  public void runAsync_resumable_transferToParent_withDisallowTransferToParent_fails() {
    TestLlm rootLlm = TestLlm.create(transferTo("sub"));
    Runner runner = resumableRunner(rootWithSubDisallowingParent(rootLlm));
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'sub' is not allowed to transfer to the requested agent.");
    assertThat(rootLlm.getRequests()).hasSize(1);
  }

  @Test
  public void runAsync_transferToPeer_withDisallowTransferToPeers_failsAndPeerNeverRuns() {
    TestLlm peerLlm = TestLlm.create(ImmutableList.of());
    Runner runner =
        new InMemoryRunner(
            rootWithSubDisallowingPeers(TestLlm.create(transferTo("peer")), peerLlm), "test");
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'sub' is not allowed to transfer to the requested agent.");
    assertThat(error.agentName()).isEqualTo("sub");
    assertThat(peerLlm.getRequests()).isEmpty();
  }

  @Test
  public void
      runAsync_resumable_transferToPeer_withDisallowTransferToPeers_failsAndPeerNeverRuns() {
    TestLlm peerLlm = TestLlm.create(ImmutableList.of());
    Runner runner =
        resumableRunner(rootWithSubDisallowingPeers(TestLlm.create(transferTo("peer")), peerLlm));
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'sub' is not allowed to transfer to the requested agent.");
    assertThat(peerLlm.getRequests()).isEmpty();
  }

  // The model chose the name, so the error names only the calling agent.
  @Test
  public void runAsync_transferToUnknownAgent_failsAsNotInTree() {
    Runner runner =
        new InMemoryRunner(root(TestLlm.create(transferTo("missing")), textAgent("sub")), "test");
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'root' requested a transfer to an agent that is not in the agent tree.");
  }

  @Test
  public void runAsync_resumable_transferToUnknownAgent_failsAsNotInTree() {
    Runner runner = resumableRunner(root(TestLlm.create(transferTo("missing")), textAgent("sub")));
    Session session = newSession(runner);

    TransferRejectedException error =
        assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'root' requested a transfer to an agent that is not in the agent tree.");
  }

  @Test
  public void runAsync_transferToDuplicateName_runsTheDeclaredTarget() {
    TestLlm declaredLlm = TestLlm.create(createTextLlmResponse("declared ran"));
    TestLlm namesakeLlm = TestLlm.create(createTextLlmResponse("namesake ran"));
    Runner runner = new InMemoryRunner(rootWithNamesakeTargets(declaredLlm, namesakeLlm), "test");

    runTurn(runner, newSession(runner), "hi");

    assertThat(declaredLlm.getRequests()).hasSize(1);
    assertThat(namesakeLlm.getRequests()).isEmpty();
  }

  @Test
  public void runAsync_resumable_transferToDuplicateName_runsTheDeclaredTarget() {
    TestLlm declaredLlm = TestLlm.create(createTextLlmResponse("declared ran"));
    TestLlm namesakeLlm = TestLlm.create(createTextLlmResponse("namesake ran"));
    Runner runner = resumableRunner(rootWithNamesakeTargets(declaredLlm, namesakeLlm));

    runTurn(runner, newSession(runner), "hi");

    assertThat(declaredLlm.getRequests()).hasSize(1);
    assertThat(namesakeLlm.getRequests()).isEmpty();
  }

  // After a rejected transfer, the next turn runs the agent that tried it.
  @Test
  public void runAsync_afterRejectedPeerTransfer_nextTurnRunsTheTransferringAgent() {
    TestLlm subLlm = TestLlm.create(transferTo("peer"), createTextLlmResponse("sub turn 2"));
    TestLlm peerLlm = TestLlm.create(ImmutableList.of());
    Runner runner = new InMemoryRunner(rootWithSubDisallowingPeers(subLlm, peerLlm), "test");
    Session session = newSession(runner);
    assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "turn 1"));

    ImmutableList<Event> turn2 = runTurn(runner, session, "turn 2");

    assertThat(simplifyEvents(turn2)).containsExactly("sub: sub turn 2");
    assertThat(peerLlm.getRequests()).isEmpty();
  }

  @Test
  public void runAsync_resumable_afterRejectedPeerTransfer_nextTurnRunsTheTransferringAgent() {
    TestLlm subLlm = TestLlm.create(transferTo("peer"), createTextLlmResponse("sub turn 2"));
    TestLlm peerLlm = TestLlm.create(ImmutableList.of());
    Runner runner = resumableRunner(rootWithSubDisallowingPeers(subLlm, peerLlm));
    Session session = newSession(runner);
    assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "turn 1"));

    ImmutableList<Event> turn2 = runTurn(runner, session, "turn 2");

    assertThat(simplifyResumableEvents(turn2))
        .containsExactly("sub: sub turn 2", "sub: " + END_OF_AGENT)
        .inOrder();
    assertThat(peerLlm.getRequests()).isEmpty();
  }

  // A rejected transfer ends the agent that made it, so resuming the invocation runs nothing.
  @Test
  public void resume_afterRejectedSelfTransfer_runsNothing() {
    TestLlm rootLlm = TestLlm.create(transferTo("root"));
    Runner runner = resumableRunner(root(rootLlm, textAgent("sub")));
    Session session = newSession(runner);
    assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));
    String invocationId = reloadSession(runner, session).immutableEvents().get(0).invocationId();

    ImmutableList<Event> resumed = resumeById(runner, session, invocationId);

    assertThat(resumed).isEmpty();
    assertThat(rootLlm.getRequests()).hasSize(1);
  }

  @Test
  public void resume_afterTransferToUnknownAgent_runsNothing() {
    TestLlm rootLlm = TestLlm.create(transferTo("missing"));
    Runner runner = resumableRunner(root(rootLlm, textAgent("sub")));
    Session session = newSession(runner);
    assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));
    String invocationId = reloadSession(runner, session).immutableEvents().get(0).invocationId();

    ImmutableList<Event> resumed = resumeById(runner, session, invocationId);

    assertThat(resumed).isEmpty();
    assertThat(rootLlm.getRequests()).hasSize(1);
  }

  // The transferring sub-agent ends before the run fails, so a resume only closes the root.
  @Test
  public void resume_afterRejectedPeerTransfer_runsNeitherThePeerNorTheTransferringAgent() {
    TestLlm subLlm = TestLlm.create(transferTo("peer"));
    TestLlm peerLlm = TestLlm.create(ImmutableList.of());
    Runner runner = resumableRunner(rootWithSubDisallowingPeers(subLlm, peerLlm));
    Session session = newSession(runner);
    assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));
    ImmutableList<Event> firstTurnEvents = reloadSession(runner, session).immutableEvents();
    ImmutableList<String> firstTurn = simplifyResumableEvents(firstTurnEvents);
    String invocationId = firstTurnEvents.get(0).invocationId();

    ImmutableList<Event> resumed = resumeById(runner, session, invocationId);

    assertThat(firstTurn.subList(firstTurn.size() - 2, firstTurn.size()))
        .containsExactly(
            "sub: FunctionResponse(name=transfer_to_agent, response={})", "sub: " + END_OF_AGENT)
        .inOrder();
    assertThat(simplifyResumableEvents(resumed)).containsExactly("root: " + END_OF_AGENT);
    assertThat(subLlm.getRequests()).hasSize(1);
    assertThat(peerLlm.getRequests()).isEmpty();
  }

  // Under a ParallelAgent, sub writes its end marker on a descendant branch of root's.
  @Test
  public void resume_underParallelAgent_afterRejectedPeerTransfer_onlyClosesTheAncestors() {
    TestLlm subLlm = TestLlm.create(transferTo("peer"));
    TestLlm peerLlm = TestLlm.create(ImmutableList.of());
    ParallelAgent par =
        ParallelAgent.builder()
            .name("par")
            .subAgents(ImmutableList.of(rootWithSubDisallowingPeers(subLlm, peerLlm)))
            .build();
    Runner runner = resumableRunner(par);
    Session session = newSession(runner);
    assertThrows(TransferRejectedException.class, () -> runTurn(runner, session, "hi"));
    String invocationId = reloadSession(runner, session).immutableEvents().get(0).invocationId();

    ImmutableList<Event> resumed = resumeById(runner, session, invocationId);

    assertThat(simplifyResumableEvents(resumed))
        .containsExactly("root: " + END_OF_AGENT, "par: " + END_OF_AGENT)
        .inOrder();
    assertThat(subLlm.getRequests()).hasSize(1);
    assertThat(peerLlm.getRequests()).isEmpty();
  }

  // Only a rejected transfer ends the agent; after any other failure a resume runs it again.
  @Test
  public void resume_afterModelError_runsTheAgentAgain() {
    TestLlm rootLlm =
        TestLlm.create(/* responses= */ null, new IllegalStateException("model failed"));
    Runner runner = resumableRunner(root(rootLlm, textAgent("sub")));
    Session session = newSession(runner);
    assertThrows(IllegalStateException.class, () -> runTurn(runner, session, "hi"));
    ImmutableList<Event> firstTurnEvents = reloadSession(runner, session).immutableEvents();
    String invocationId = firstTurnEvents.get(0).invocationId();

    assertThrows(IllegalStateException.class, () -> resumeById(runner, session, invocationId));

    assertThat(simplifyResumableEvents(firstTurnEvents)).doesNotContain("root: " + END_OF_AGENT);
    assertThat(rootLlm.getRequests()).hasSize(2);
  }

  @Test
  public void resume_afterToolError_runsTheAgentAgain() {
    TestLlm rootLlm =
        TestLlm.create(
            createLlmResponse(Content.fromParts(Part.fromFunctionCall("fail", ImmutableMap.of()))));
    FailingTool tool = new FailingTool();
    LlmAgent root =
        createTestAgentBuilder(rootLlm)
            .name("root")
            .tools(tool)
            .subAgents(textAgent("sub"))
            .build();
    Runner runner = resumableRunner(root);
    Session session = newSession(runner);
    assertThrows(IllegalStateException.class, () -> runTurn(runner, session, "hi"));
    ImmutableList<Event> firstTurnEvents = reloadSession(runner, session).immutableEvents();
    String invocationId = firstTurnEvents.get(0).invocationId();

    // The resume replays the pending call, so the tool fails again.
    assertThrows(IllegalStateException.class, () -> resumeById(runner, session, invocationId));

    assertThat(simplifyResumableEvents(firstTurnEvents)).doesNotContain("root: " + END_OF_AGENT);
    assertThat(tool.calls.get()).isEqualTo(2);
  }

  private static LlmResponse transferTo(String agentName) {
    return createLlmResponse(Content.fromParts(createTransferCallPart(agentName)));
  }

  private static LlmAgent root(TestLlm llm, BaseAgent... subAgents) {
    return createTestAgentBuilder(llm).name("root").subAgents(subAgents).build();
  }

  /** A root whose model transfers to its grandchild, which runs on {@code grandchildLlm}. */
  private static LlmAgent rootTransferringToGrandchild(TestLlm grandchildLlm) {
    LlmAgent grandchild = createTestAgentBuilder(grandchildLlm).name("grandchild").build();
    LlmAgent sub =
        createTestAgentBuilder(TestLlm.create(ImmutableList.of()))
            .name("sub")
            .subAgents(grandchild)
            .build();
    return root(TestLlm.create(transferTo("grandchild")), sub);
  }

  /** A root, on {@code rootLlm}, whose sub-agent transfers back despite disallowing it. */
  private static LlmAgent rootWithSubDisallowingParent(TestLlm rootLlm) {
    LlmAgent sub =
        createTestAgentBuilder(TestLlm.create(transferTo("root")))
            .name("sub")
            .disallowTransferToParent(true)
            .build();
    // Gives sub a target, so it is offered the transfer tool.
    return root(rootLlm, sub, textAgent("peer"));
  }

  /** A root that transfers to {@code sub}, on {@code subLlm}, which disallows peer transfers. */
  private static LlmAgent rootWithSubDisallowingPeers(TestLlm subLlm, TestLlm peerLlm) {
    LlmAgent sub = createTestAgentBuilder(subLlm).name("sub").disallowTransferToPeers(true).build();
    LlmAgent peer = createTestAgentBuilder(peerLlm).name("peer").build();
    return root(TestLlm.create(transferTo("sub")), sub, peer);
  }

  /**
   * A root whose sub-agent "shared" runs on {@code declaredLlm}, a deeper one on {@code
   * namesakeLlm}.
   */
  private static LlmAgent rootWithNamesakeTargets(TestLlm declaredLlm, TestLlm namesakeLlm) {
    LlmAgent otherBranch =
        createTestAgentBuilder(TestLlm.create(ImmutableList.of()))
            .name("other_branch")
            .subAgents(createTestAgentBuilder(namesakeLlm).name("shared").build())
            .build();
    LlmAgent declared = createTestAgentBuilder(declaredLlm).name("shared").build();
    // other_branch comes first, so a depth-first search of the tree reaches the namesake first.
    return root(TestLlm.create(transferTo("shared")), otherBranch, declared);
  }

  private Runner getRunnerAndCreateSession(LlmAgent agent, Session session) {
    Runner runner = new InMemoryRunner(agent, session.appName());
    // Ensure the session exists before running the agent.
    var unused =
        runner
            .sessionService()
            .createSession(session.appName(), session.userId(), session.state(), session.id())
            .blockingGet(); // Block to ensure session creation completes.
    return runner;
  }

  private List<Event> runRunner(Runner runner, InvocationContext invocationContext) {
    Session session = invocationContext.session();
    RunConfig runConfig = RunConfig.builder().build(); // Default RunConfig

    return runner
        .runAsync(
            session.userId(), session.id(), invocationContext.userContent().orElse(null), runConfig)
        .toList()
        .blockingGet();
  }
}
