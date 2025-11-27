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

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  @Test
  public void exitLoopTool_exitsLoop() {
    Content generatedContent =
        Content.fromParts(
            Part.fromText("Mock LLM Response:I will call the exit_loop tool."),
            Part.fromFunctionCall("exit_loop", ImmutableMap.of()));

    TestLlm unusedTestLlm = createTestLlm(createLlmResponse(generatedContent));
    // InvocationContext unusedInvocationContext =
    // createInvocationContext(createTestAgent(testLlm));
    // TODO: b/413488103 - complete when LoopAgent is implemented.
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
