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

package com.google.adk.agents;

import static com.google.adk.testing.TestUtils.assertEqualIgnoringFunctionIds;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.Callbacks.OnModelErrorCallback;
import com.google.adk.agents.Callbacks.OnToolErrorCallback;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.Model;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LlmAgent}. */
@RunWith(JUnit4.class)
public final class LlmAgentTest {

  @Test
  public void testRun_withNoCallbacks() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgent(testLlm);
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(modelContent);
  }

  @Test
  public void testRun_withOutputKey_savesState() {
    Content modelContent = Content.fromParts(Part.fromText("Saved output"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).outputKey("myOutput").build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta()).containsEntry("myOutput", "Saved output");
  }

  @Test
  public void testRun_withOutputKey_savesMultiPartState() {
    Content modelContent = Content.fromParts(Part.fromText("Part 1."), Part.fromText(" Part 2."));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).outputKey("myMultiPartOutput").build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta())
        .containsEntry("myMultiPartOutput", "Part 1. Part 2.");
  }

  @Test
  public void testRun_withOutputKey_savesState_ignoresThoughts() {
    Content modelContent =
        Content.fromParts(
            Part.fromText("Saved output"),
            Part.fromText("Ignored thought").toBuilder().thought(true).build());
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).outputKey("myOutput").build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta()).containsEntry("myOutput", "Saved output");
  }

  @Test
  public void testRun_withoutOutputKey_doesNotSaveState() {
    Content modelContent = Content.fromParts(Part.fromText("Some output"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta()).isEmpty();
  }

  @Test
  public void run_withToolsAndMaxSteps_stopsAfterMaxSteps() {
    ImmutableMap<String, Object> echoArgs = ImmutableMap.of("arg", "value");
    Content contentWithFunctionCall =
        Content.fromParts(Part.fromText("text"), Part.fromFunctionCall("echo_tool", echoArgs));
    Content unreachableContent = Content.fromParts(Part.fromText("This should never be returned."));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(contentWithFunctionCall),
            createLlmResponse(contentWithFunctionCall),
            createLlmResponse(unreachableContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).tools(new EchoTool()).maxSteps(2).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    Content expectedFunctionResponseContent =
        Content.fromParts(
            Part.fromFunctionResponse(
                "echo_tool", ImmutableMap.<String, Object>of("result", echoArgs)));
    assertThat(events).hasSize(4);
    assertEqualIgnoringFunctionIds(events.get(0).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(events.get(1).content().get(), expectedFunctionResponseContent);
    assertEqualIgnoringFunctionIds(events.get(2).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(events.get(3).content().get(), expectedFunctionResponseContent);
  }

  @Test
  public void build_withOutputSchemaAndTools_throwsIllegalArgumentException() {
    BaseTool tool =
        new BaseTool("test_tool", "test_description") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.empty();
          }
        };

    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("status", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("status"))
            .build();

    // Expecting an IllegalArgumentException when building the agent
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LlmAgent.builder() // Use the agent builder directly
                    .name("agent with invalid tool config")
                    .outputSchema(outputSchema) // Set the output schema
                    .tools(ImmutableList.of(tool)) // Set tools (this should cause the error)
                    .build()); // Attempt to build the agent

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Invalid config for agent agent with invalid tool config: if outputSchema is set, tools"
                + " must be empty");
  }

  @Test
  public void build_withOutputSchemaAndSubAgents_throwsIllegalArgumentException() {
    ImmutableList<BaseAgent> subAgents =
        ImmutableList.of(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .name("test_sub_agent")
                .description("test_sub_agent_description")
                .build());

    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("status", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("status"))
            .build();

    // Expecting an IllegalArgumentException when building the agent
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LlmAgent.builder() // Use the agent builder directly
                    .name("agent with invalid tool config")
                    .outputSchema(outputSchema) // Set the output schema
                    .subAgents(subAgents) // Set subAgents (this should cause the error)
                    .build()); // Attempt to build the agent

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Invalid config for agent agent with invalid tool config: if outputSchema is set,"
                + " subAgents must be empty to disable agent transfer.");
  }

  @Test
  public void testBuild_withNullInstruction_setsInstructionToEmptyString() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction((String) null)
            .build();

    assertThat(agent.instruction()).isEqualTo(new Instruction.Static(""));
  }

  @Test
  public void testCanonicalInstruction_acceptsPlainString() {
    String instruction = "Test static instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }

  @Test
  public void testBuild_withNullGlobalInstruction_setsGlobalInstructionToEmptyString() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction((String) null)
            .build();

    assertThat(agent.globalInstruction()).isEqualTo(new Instruction.Static(""));
  }

  @Test
  public void testCanonicalGlobalInstruction_acceptsPlainString() {
    String instruction = "Test static global instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalGlobalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalGlobalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider global instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalGlobalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }

  @Test
  public void resolveModel_withModelName_resolvesFromRegistry() {
    String modelName = "test-model";
    TestLlm testLlm = createTestLlm(LlmResponse.builder().build());
    LlmRegistry.registerLlm(modelName, (unusedName) -> testLlm);
    LlmAgent agent = createTestAgentBuilder(testLlm).model(modelName).build();
    Model resolvedModel = agent.resolvedModel();

    assertThat(resolvedModel.modelName()).hasValue(modelName);
    assertThat(resolvedModel.model()).hasValue(testLlm);
  }

  @Test
  public void canonicalCallbacks_returnsEmptyListWhenNull() {
    TestLlm testLlm = createTestLlm(LlmResponse.builder().build());
    LlmAgent agent = createTestAgent(testLlm);

    assertThat(agent.canonicalBeforeModelCallbacks()).isEmpty();
    assertThat(agent.canonicalAfterModelCallbacks()).isEmpty();
    assertThat(agent.canonicalOnModelErrorCallbacks()).isEmpty();
    assertThat(agent.canonicalBeforeToolCallbacks()).isEmpty();
    assertThat(agent.canonicalAfterToolCallbacks()).isEmpty();
    assertThat(agent.canonicalOnToolErrorCallbacks()).isEmpty();
  }

  @Test
  public void canonicalCallbacks_returnsListWhenPresent() {
    BeforeModelCallback bmc = (unusedCtx, unusedReq) -> Maybe.empty();
    AfterModelCallback amc = (unusedCtx, unusedRes) -> Maybe.empty();
    OnModelErrorCallback omec = (unusedCtx, unusedReq, unusedErr) -> Maybe.empty();
    BeforeToolCallback btc = (unusedInvCtx, unusedTool, unusedArgs, unusedToolCtx) -> Maybe.empty();
    AfterToolCallback atc =
        (unusedInvCtx, unusedTool, unusedArgs, unusedToolCtx, unusedRes) -> Maybe.empty();
    OnToolErrorCallback otec =
        (unusedInvCtx, unusedTool, unusedArgs, unusedToolCtx, unusedErr) -> Maybe.empty();

    TestLlm testLlm = createTestLlm(LlmResponse.builder().build());
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeModelCallback(ImmutableList.of(bmc))
            .afterModelCallback(ImmutableList.of(amc))
            .onModelErrorCallback(ImmutableList.of(omec))
            .beforeToolCallback(ImmutableList.of(btc))
            .afterToolCallback(ImmutableList.of(atc))
            .onToolErrorCallback(ImmutableList.of(otec))
            .build();

    assertThat(agent.canonicalBeforeModelCallbacks()).containsExactly(bmc);
    assertThat(agent.canonicalAfterModelCallbacks()).containsExactly(amc);
    assertThat(agent.canonicalOnModelErrorCallbacks()).containsExactly(omec);
    assertThat(agent.canonicalBeforeToolCallbacks()).containsExactly(btc);
    assertThat(agent.canonicalAfterToolCallbacks()).containsExactly(atc);
    assertThat(agent.canonicalOnToolErrorCallbacks()).containsExactly(otec);
  }

  @Test
  public void run_sequentialAgents_shareTempStateViaSession() {
    // 1. Setup Session Service and Session
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session =
        sessionService
            .createSession("app", "user", new ConcurrentHashMap<>(), "session1")
            .blockingGet();

    // 2. Agent 1: runs and produces output "value1" to state "temp:key1"
    Content model1Content = Content.fromParts(Part.fromText("value1"));
    TestLlm testLlm1 = createTestLlm(createLlmResponse(model1Content));
    LlmAgent agent1 =
        createTestAgentBuilder(testLlm1).name("agent1").outputKey("temp:key1").build();
    InvocationContext invocationContext1 = createInvocationContext(agent1, sessionService, session);

    List<Event> events1 = agent1.runAsync(invocationContext1).toList().blockingGet();
    assertThat(events1).hasSize(1);
    Event event1 = events1.get(0);
    assertThat(event1.actions()).isNotNull();
    assertThat(event1.actions().stateDelta()).containsEntry("temp:key1", "value1");

    // 3. Simulate orchestrator: append event1 to session, updating its state
    var unused = sessionService.appendEvent(session, event1).blockingGet();
    assertThat(session.state()).containsEntry("temp:key1", "value1");

    // 4. Agent 2: uses Instruction.Provider to read "temp:key1" from session state
    // and generates an instruction based on it.
    TestLlm testLlm2 =
        createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("response2"))));
    LlmAgent agent2 =
        createTestAgentBuilder(testLlm2)
            .name("agent2")
            .instruction(
                new Instruction.Provider(
                    ctx ->
                        Single.just(
                            "Instruction for Agent2 based on Agent1 output: "
                                + ctx.state().get("temp:key1"))))
            .build();
    InvocationContext invocationContext2 = createInvocationContext(agent2, sessionService, session);
    List<Event> events2 = agent2.runAsync(invocationContext2).toList().blockingGet();
    assertThat(events2).hasSize(1);

    // 5. Verify that agent2's LLM received an instruction containing agent1's output
    assertThat(testLlm2.getRequests()).hasSize(1);
    LlmRequest request2 = testLlm2.getRequests().get(0);
    assertThat(request2.getFirstSystemInstruction().get())
        .contains("Instruction for Agent2 based on Agent1 output: value1");
  }
}
