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

import static com.google.adk.testing.TestUtils.createEvent;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createResumableInvocationContext;
import static com.google.adk.testing.TestUtils.createStreamingTextLlmResponses;
import static com.google.adk.testing.TestUtils.createSubAgent;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.runRequestingLate;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.RecordingScheduler;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SequentialAgent}. */
@RunWith(JUnit4.class)
public final class SequentialAgentTest {

  private static final String APP_NAME = "app";
  private static final String NOT_RUN = "<reader did not run>";
  private static final RunConfig SSE =
      RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build();

  @Test
  public void runAsync_withNoSubAgents_returnsEmptyEvents() {
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seqAgent").subAgents(ImmutableList.of()).build();
    InvocationContext invocationContext = createInvocationContext(sequentialAgent);
    List<Event> events = sequentialAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).isEmpty();
  }

  @Test
  public void runAsync_withSingleSubAgent_returnsEventsFromSubAgent() {
    Event event1 = createEvent("event1").toBuilder().author("subAgent").build();
    TestBaseAgent subAgent = createSubAgent("subAgent", event1);
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seqAgent").subAgents(ImmutableList.of(subAgent)).build();
    InvocationContext invocationContext = createInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).containsExactly(event1);
    assertThat(events.get(0).author()).isEqualTo("subAgent");
  }

  @Test
  public void runAsync_withSingleLlmSubAgent_returnsEventsFromSubAgent() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent subAgent = createTestAgent(testLlm);
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seqAgent").subAgents(ImmutableList.of(subAgent)).build();
    InvocationContext invocationContext = createInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(modelContent);
  }

  @Test
  public void runAsync_withMultipleSubAgents_returnsConcatenatedEventsInOrder() {
    Event event1 = createEvent("event1");
    Event event2 = createEvent("event2");
    Event event3 = createEvent("event3");

    TestBaseAgent subAgent1 =
        createSubAgent(
            "subAgent",
            event1.toBuilder().author("subAgent").build(),
            event2.toBuilder().author("subAgent").build());
    TestBaseAgent subAgent2 =
        createSubAgent("subAgent2", event3.toBuilder().author("subAgent2").build());
    SequentialAgent sequentialAgent =
        SequentialAgent.builder()
            .name("seqAgent")
            .subAgents(ImmutableList.of(subAgent1, subAgent2))
            .build();
    InvocationContext invocationContext = createInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertThat(events.get(0).id()).isEqualTo("event1");
    assertThat(events.get(0).author()).isEqualTo("subAgent");
    assertThat(events.get(1).id()).isEqualTo("event2");
    assertThat(events.get(1).author()).isEqualTo("subAgent");
    assertThat(events.get(2).id()).isEqualTo("event3");
    assertThat(events.get(2).author()).isEqualTo("subAgent2");
  }

  @Test
  public void runAsync_propagatesInvocationContextToSubAgents() {
    TestBaseAgent subAgent = createSubAgent("subAgent");
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seqAgent").subAgents(ImmutableList.of(subAgent)).build();
    InvocationContext parentContext = createInvocationContext(sequentialAgent);

    List<Event> unused = sequentialAgent.runAsync(parentContext).toList().blockingGet();

    InvocationContext capturedContext = subAgent.getLastInvocationContext();
    assertThat(capturedContext).isNotNull();
    assertThat(capturedContext.invocationId()).isEqualTo(parentContext.invocationId());
    assertThat(capturedContext.session()).isEqualTo(parentContext.session());
    assertThat(capturedContext.agent()).isEqualTo(subAgent);
    assertThat(subAgent.getInvocationCount()).isEqualTo(1);
  }

  @Test
  public void runLive_withNoSubAgents_returnsEmptyEvents() {
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seqAgent").subAgents(ImmutableList.of()).build();
    InvocationContext invocationContext = createInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runLive(invocationContext).toList().blockingGet();

    assertThat(events).isEmpty();
  }

  @Test
  public void runLive_withSingleSubAgent_returnsEventsFromSubAgent() {
    Event event1 = createEvent("event1_live").toBuilder().author("subAgent_live").build();
    TestBaseAgent subAgent = createSubAgent("subAgent_live", event1);
    SequentialAgent sequentialAgent =
        SequentialAgent.builder()
            .name("seqAgentLive")
            .subAgents(ImmutableList.of(subAgent))
            .build();
    InvocationContext invocationContext = createInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runLive(invocationContext).toList().blockingGet();

    assertThat(events).containsExactly(event1);
    assertThat(events.get(0).author()).isEqualTo("subAgent_live");
  }

  @Test
  public void runLive_withMultipleSubAgents_returnsConcatenatedEventsInOrder() {
    Event event1 = createEvent("event1_live");
    Event event2 = createEvent("event2_live");
    Event event3 = createEvent("event3_live");
    TestBaseAgent subAgent1 =
        createSubAgent(
            "subAgent_live",
            event1.toBuilder().author("subAgent_live").build(),
            event2.toBuilder().author("subAgent_live").build());
    TestBaseAgent subAgent2 =
        createSubAgent("subAgent2_live", event3.toBuilder().author("subAgent2_live").build());
    SequentialAgent sequentialAgent =
        SequentialAgent.builder()
            .name("seqAgentLive")
            .subAgents(ImmutableList.of(subAgent1, subAgent2))
            .build();
    InvocationContext invocationContext = createInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runLive(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertThat(events.get(0).id()).isEqualTo("event1_live");
    assertThat(events.get(0).author()).isEqualTo("subAgent_live");
    assertThat(events.get(1).id()).isEqualTo("event2_live");
    assertThat(events.get(1).author()).isEqualTo("subAgent_live");
    assertThat(events.get(2).id()).isEqualTo("event3_live");
    assertThat(events.get(2).author()).isEqualTo("subAgent2_live");
  }

  @Test
  public void runLive_propagatesInvocationContextToSubAgents() {
    TestBaseAgent subAgent = createSubAgent("subAgent_live");
    SequentialAgent sequentialAgent =
        SequentialAgent.builder()
            .name("seqAgentLive")
            .subAgents(ImmutableList.of(subAgent))
            .build();
    InvocationContext parentContext = createInvocationContext(sequentialAgent);

    List<Event> unused = sequentialAgent.runLive(parentContext).toList().blockingGet();

    InvocationContext capturedContext = subAgent.getLastInvocationContext();
    assertThat(capturedContext).isNotNull();
    assertThat(capturedContext.invocationId()).isEqualTo(parentContext.invocationId());
    assertThat(capturedContext.session()).isEqualTo(parentContext.session());
    assertThat(capturedContext.agent()).isEqualTo(subAgent);
    assertThat(subAgent.getInvocationCount()).isEqualTo(1);
  }

  // orElse(0) masks the exact index end to end, so assert the helper directly.
  @Test
  public void resumeSubAgentIndex_authorIsFirstSubAgent_returnsZero() {
    TestBaseAgent first = createSubAgent("first_agent");
    TestBaseAgent second = createSubAgent("second_agent");
    SequentialAgent root =
        SequentialAgent.builder().name("root").subAgents(ImmutableList.of(first, second)).build();

    InvocationContext context = contextResumingCall(root, "first_agent");

    assertThat(WorkflowAgentResumption.resumeSubAgentIndex(context, root.subAgents())).hasValue(0);
  }

  @Test
  public void resumeSubAgentIndex_authorNestedInLaterSubAgent_returnsThatSubAgentIndex() {
    TestBaseAgent first = createSubAgent("first_agent");
    TestBaseAgent nested = createSubAgent("nested_agent");
    SequentialAgent branch =
        SequentialAgent.builder().name("branch_agent").subAgents(ImmutableList.of(nested)).build();
    SequentialAgent root =
        SequentialAgent.builder().name("root").subAgents(ImmutableList.of(first, branch)).build();

    InvocationContext context = contextResumingCall(root, "nested_agent");

    assertThat(WorkflowAgentResumption.resumeSubAgentIndex(context, root.subAgents())).hasValue(1);
  }

  @Test
  public void resumeSubAgentIndex_noMatchingAuthor_returnsEmpty() {
    TestBaseAgent first = createSubAgent("first_agent");
    TestBaseAgent second = createSubAgent("second_agent");
    SequentialAgent root =
        SequentialAgent.builder().name("root").subAgents(ImmutableList.of(first, second)).build();

    InvocationContext context = contextResumingCall(root, "unknown_agent");

    assertThat(WorkflowAgentResumption.resumeSubAgentIndex(context, root.subAgents())).isEmpty();
  }

  @Test
  public void resumeSubAgentIndex_branchedNestedAuthor_returnsThatSubAgentIndex() {
    // A Sequential nested inside a Parallel carries a branch a filter would hide.
    TestBaseAgent first = createSubAgent("first_agent");
    TestBaseAgent nested = createSubAgent("nested_agent");
    SequentialAgent branch =
        SequentialAgent.builder().name("branch_agent").subAgents(ImmutableList.of(nested)).build();
    SequentialAgent root =
        SequentialAgent.builder().name("root").subAgents(ImmutableList.of(first, branch)).build();

    InvocationContext context =
        contextResumingBranchedCall(root, "nested_agent", "p.root", "p.root.branch_agent");

    assertThat(WorkflowAgentResumption.resumeSubAgentIndex(context, root.subAgents())).hasValue(1);
  }

  // Session ending with a function response that resumes a call authored by callAuthor.
  private static InvocationContext contextResumingCall(BaseAgent rootAgent, String callAuthor) {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession("test_app", "test-user").blockingGet();
    Event callEvent =
        Event.builder()
            .id("call_event")
            .invocationId("invocationId")
            .author(callAuthor)
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(FunctionCall.builder().id("call_id").name("tool").build())
                        .build()))
            .build();
    Event responseEvent =
        Event.builder()
            .id("response_event")
            .invocationId("invocationId")
            .author("user")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("call_id")
                                .name("tool")
                                .response(ImmutableMap.of())
                                .build())
                        .build()))
            .build();
    var unusedCall = sessionService.appendEvent(session, callEvent).blockingGet();
    var unusedResponse = sessionService.appendEvent(session, responseEvent).blockingGet();
    return createInvocationContext(rootAgent, sessionService, session);
  }

  // As above, but the context sits on contextBranch and the call event on callBranch.
  private static InvocationContext contextResumingBranchedCall(
      BaseAgent rootAgent, String callAuthor, String contextBranch, String callBranch) {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession("test_app", "test-user").blockingGet();
    Event callEvent =
        Event.builder()
            .id("call_event")
            .invocationId("invocationId")
            .author(callAuthor)
            .branch(callBranch)
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(FunctionCall.builder().id("call_id").name("tool").build())
                        .build()))
            .build();
    Event responseEvent =
        Event.builder()
            .id("response_event")
            .invocationId("invocationId")
            .author("user")
            .branch(contextBranch)
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("call_id")
                                .name("tool")
                                .response(ImmutableMap.of())
                                .build())
                        .build()))
            .build();
    var unusedCall = sessionService.appendEvent(session, callEvent).blockingGet();
    var unusedResponse = sessionService.appendEvent(session, responseEvent).blockingGet();
    InvocationContext context = createInvocationContext(rootAgent, sessionService, session);
    context.branch(contextBranch);
    return context;
  }

  // ---- Resumability: durable checkpoint resume. ----

  @Test
  public void runAsync_resumingFromMiddle_startsFromCorrectAgent() {
    LlmAgent agent1 =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("a1 done")))
            .name("agent1")
            .build();
    LlmAgent agent2 =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("a2 done")))
            .name("agent2")
            .build();
    LlmAgent agent3 =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("a3 done")))
            .name("agent3")
            .build();
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seq").subAgents(agent1, agent2, agent3).build();
    InvocationContext context = createResumableInvocationContext(sequentialAgent);
    // Seed the checkpoint: resume at agent2.
    context.setAgentState(
        "seq", ImmutableMap.of("current_sub_agent", "agent2"), /* endOfAgent= */ false);

    List<Event> events = sequentialAgent.runAsync(context).toList().blockingGet();

    assertThat(simplifyEvents(events)).doesNotContain("agent1: a1 done");
    assertThat(simplifyEvents(events)).contains("agent2: a2 done");
    assertThat(simplifyEvents(events)).contains("agent3: a3 done");
  }

  // A null name is not "finished": Python's model rejects it, so a resume must not silently skip
  // every sub-agent.
  @Test
  public void runAsync_resumingFromNullSubAgentName_throws() {
    LlmAgent agent1 =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("a1 done")))
            .name("agent1")
            .build();
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seq").subAgents(agent1).build();
    InvocationContext context = createResumableInvocationContext(sequentialAgent);
    Map<String, Object> checkpoint = new HashMap<>();
    checkpoint.put("current_sub_agent", null);
    context.setAgentState("seq", checkpoint, /* endOfAgent= */ false);

    var resumed = sequentialAgent.runAsync(context).toList();

    assertThrows(IllegalStateException.class, resumed::blockingGet);
  }

  @Test
  public void runAsync_resumable_emitsEndOfAgent() {
    LlmAgent agent1 =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("done"))).name("agent1").build();
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seq").subAgents(agent1).build();
    InvocationContext context = createResumableInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runAsync(context).toList().blockingGet();

    assertThat(
            events.stream()
                .anyMatch(event -> event.author().equals("seq") && event.actions().endOfAgent()))
        .isTrue();
  }

  @Test
  public void runAsync_notResumable_doesNotEmitEndOfAgent() {
    LlmAgent agent1 =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("done"))).name("agent1").build();
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seq").subAgents(agent1).build();
    InvocationContext context = createInvocationContext(sequentialAgent);

    List<Event> events = sequentialAgent.runAsync(context).toList().blockingGet();

    assertThat(events.stream().anyMatch(event -> event.actions().endOfAgent())).isFalse();
  }

  // Legacy resumption fast-forwards from history; the resumable flow uses its own checkpoints.
  @Test
  @SuppressWarnings("deprecation") // The legacy shim is deprecated by design.
  public void runAsync_legacyResumption_doesNotRerunCompletedSubAgents() {
    TestBaseAgent agent1 =
        createSubAgent("agent1", createEvent("a1").toBuilder().author("agent1").build());
    TestBaseAgent agent2 =
        createSubAgent("agent2", createEvent("a2").toBuilder().author("agent2").build());
    TestBaseAgent agent3 =
        createSubAgent("agent3", createEvent("a3").toBuilder().author("agent3").build());
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seq").subAgents(agent1, agent2, agent3).build();

    // A legacy paused session: agent2's long-running call, now answered, with no checkpoints.
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession("app", "user").blockingGet();
    var unusedCall =
        sessionService
            .appendEvent(
                session,
                Event.builder()
                    .id("fc")
                    .invocationId("inv")
                    .author("agent2")
                    .content(
                        Content.fromParts(
                            Part.builder()
                                .functionCall(FunctionCall.builder().id("c1").name("tool").build())
                                .build()))
                    .longRunningToolIds(ImmutableSet.of("c1"))
                    .build())
            .blockingGet();
    var unusedResponse =
        sessionService
            .appendEvent(
                session,
                Event.builder()
                    .id("fr")
                    .invocationId("inv")
                    .author("user")
                    .content(
                        Content.fromParts(
                            Part.builder()
                                .functionResponse(
                                    FunctionResponse.builder()
                                        .id("c1")
                                        .name("tool")
                                        .response(ImmutableMap.of())
                                        .build())
                                .build()))
                    .build())
            .blockingGet();
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(sessionService)
            .artifactService(new InMemoryArtifactService())
            .invocationId("inv")
            .agent(sequentialAgent)
            .session(session)
            .userContent(Content.fromParts(Part.fromText("resume")))
            .runConfig(RunConfig.builder().build())
            .resumabilityConfig(
                ResumabilityConfig.builder()
                    .resumable(false)
                    .plainTextContinuationAutoResume(true)
                    .build())
            .build();
    // No populate/checkpoint state seeded -- this is the legacy case.

    var unused = sequentialAgent.runAsync(context).toList().blockingGet();

    // agent1 (already completed before the pause) must not re-run; agent2 and agent3 do.
    assertThat(agent1.getInvocationCount()).isEqualTo(0);
    assertThat(agent2.getInvocationCount()).isEqualTo(1);
    assertThat(agent3.getInvocationCount()).isEqualTo(1);
  }

  // ---- Through a Runner, the next sub-agent sees the state the previous one saved. ----

  @Test
  public void runAsync_inParallelAgentWithSlowAppend_nextSubAgentSeesOutputKey() {
    LlmAgent writer =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("upstream output")))
            .name("writer")
            .outputKey("k")
            .build();
    AtomicReference<@Nullable Object> seen = new AtomicReference<>(NOT_RUN);
    // "silent" covers a sub-agent with no event to wait for.
    ParallelAgent root =
        ParallelAgent.builder()
            .name("parallel")
            .subAgents(
                SequentialAgent.builder()
                    .name("sequence")
                    .subAgents(createSubAgent("silent"), writer, stateReader("k", seen))
                    .build())
            .build();
    Runner runner =
        Runner.builder()
            .app(App.builder().name(APP_NAME).rootAgent(root).build())
            .sessionService(appendDelayingSessionService())
            .build();

    runRequestingLate(runner, RunConfig.builder().build()).assertComplete();

    assertThat(seen.get()).isEqualTo("upstream output");
  }

  @Test
  public void runAsync_afterParallelAgentWithSlowAppend_nextSubAgentSeesEveryBranchOutputKey() {
    TestLlm aggregatorLlm = createTestLlm(createTextLlmResponse("combined"));
    Runner runner =
        Runner.builder()
            .app(App.builder().name(APP_NAME).rootAgent(fanOutThenAggregate(aggregatorLlm)).build())
            .sessionService(appendDelayingSessionService())
            .build();

    runRequestingLate(runner, RunConfig.builder().build()).assertComplete();

    assertThat(String.join("\n", aggregatorLlm.getLastRequest().getSystemInstructions()))
        .contains("Combine: from a and from b");
  }

  @Test
  public void runAsync_withLateConsumer_nextAgentInstructionSeesOutputKey() {
    TestLlm editorLlm = createTestLlm(createTextLlmResponse("edited"));
    LlmAgent editor =
        createTestAgentBuilder(editorLlm).name("editor").instruction("Rewrite: {k}").build();
    SequentialAgent pipeline =
        SequentialAgent.builder()
            .name("pipeline")
            .subAgents(streamingWriter("k", "first draft"), editor)
            .build();
    Runner runner =
        Runner.builder().app(App.builder().name(APP_NAME).rootAgent(pipeline).build()).build();

    runRequestingLate(runner, SSE).assertComplete();

    assertThat(String.join("\n", editorLlm.getLastRequest().getSystemInstructions()))
        .contains("Rewrite: first draft");
  }

  @Test
  public void runAsync_resumableWithLateConsumer_nextSubAgentSeesOutputKey() {
    ResumabilityConfig resumable = ResumabilityConfig.builder().resumable(true).build();

    assertThat(outputKeySeenByNextSubAgent("saved", resumable)).isEqualTo("saved");
  }

  @Test
  @SuppressWarnings("deprecation") // The legacy shim is deprecated by design.
  public void runAsync_legacyResumptionWithLateConsumer_nextSubAgentSeesOutputKey() {
    ResumabilityConfig legacy =
        ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build();

    assertThat(outputKeySeenByNextSubAgent("saved", legacy)).isEqualTo("saved");
  }

  // A wrapper may drop partial events before they reach the Runner, so the wait must skip them.
  @Test
  public void runAsync_trailingPartialDroppedBeforeRunner_nextSubAgentStillRuns() {
    Event saved = savedEvent();
    Event trailingChunk = createEvent("chunk").toBuilder().author("writer").partial(true).build();
    AtomicReference<@Nullable Object> seen = new AtomicReference<>(NOT_RUN);
    SequentialAgent sequence =
        SequentialAgent.builder()
            .name("sequence")
            .subAgents(createSubAgent("writer", saved, trailingChunk), stateReader("k", seen))
            .build();
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name(APP_NAME)
                    .rootAgent(
                        new TransformingAgent(
                            sequence, events -> events.filter(e -> !e.partial().orElse(false))))
                    .build())
            .build();

    runRequestingLate(runner, RunConfig.builder().build()).assertComplete();

    assertThat(seen.get()).isEqualTo("saved value");
  }

  // A wrapper that drops, re-ids or holds back events must not hang the run.
  @Test
  public void runAsync_underAgentDroppingEvents_stopsWaitingAfterTimeout() {
    assertSequenceStopsWaitingUnder(events -> events.ignoreElements().<Event>toFlowable());
  }

  @Test
  public void runAsync_underAgentReIdingEvents_stopsWaitingAfterTimeout() {
    assertSequenceStopsWaitingUnder(
        events -> events.map(e -> e.toBuilder().id(UUID.randomUUID().toString()).build()));
  }

  @Test
  public void runAsync_underAgentBufferingEvents_stopsWaitingAfterTimeout() {
    assertSequenceStopsWaitingUnder(events -> events.toList().flattenAsFlowable(list -> list));
  }

  @Test
  public void runAsync_appendFails_failsOnceWithoutRunningNextSubAgent() {
    RuntimeException failure = new RuntimeException("append failed");
    BaseSessionService failingService =
        mock(BaseSessionService.class, delegatesTo(new InMemorySessionService()));
    // doReturn, because when(...) would call the delegate's real appendEvent.
    doReturn(Single.error(failure))
        .when(failingService)
        .appendEvent(any(), argThat(event -> event.author().equals("writer")));
    AtomicReference<@Nullable Object> seen = new AtomicReference<>(NOT_RUN);
    SequentialAgent sequence =
        SequentialAgent.builder()
            .name("sequence")
            .subAgents(streamingWriter("k", "upstream output"), stateReader("k", seen))
            .build();
    Runner runner =
        Runner.builder()
            .app(App.builder().name(APP_NAME).rootAgent(sequence).build())
            .sessionService(failingService)
            .build();
    List<Throwable> undelivered = new CopyOnWriteArrayList<>();
    Consumer<? super Throwable> previousHandler = RxJavaPlugins.getErrorHandler();
    RxJavaPlugins.setErrorHandler(undelivered::add);
    try {
      runRequestingLate(runner, SSE).assertError(failure);
    } finally {
      RxJavaPlugins.setErrorHandler(previousHandler);
    }

    assertThat(seen.get()).isEqualTo(NOT_RUN);
    assertThat(undelivered).isEmpty();
  }

  // An event without an id cannot be tracked, so the wait uses the last event that has one.
  @Test
  public void runAsync_lastEventHasNoId_nextSubAgentWaitsForLastEventWithId() {
    // With no demand yet, "saved" stays queued, unpersisted, behind "first".
    Event first = createEvent("first").toBuilder().author("writer").build();
    Event saved = savedEvent();
    Event withoutId = createEvent("unused").toBuilder().id(null).author("writer").build();
    AtomicReference<@Nullable Object> seen = new AtomicReference<>(NOT_RUN);
    SequentialAgent sequence =
        SequentialAgent.builder()
            .name("sequence")
            .subAgents(createSubAgent("writer", first, saved, withoutId), stateReader("k", seen))
            .build();
    Runner runner =
        Runner.builder().app(App.builder().name(APP_NAME).rootAgent(sequence).build()).build();

    runRequestingLate(runner, RunConfig.builder().build()).assertComplete();

    assertThat(seen.get()).isEqualTo("saved value");
  }

  // A late append completes on its own thread, which must not run the next sub-agent's model call.
  @Test
  public void runAsync_afterSlowAppend_nextSubAgentStartsOnInvocationScheduler() {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "invocation-scheduler"));
    try {
      AtomicReference<String> readerThread = new AtomicReference<>();
      BeforeAgentCallback recordThread =
          callbackContext -> {
            readerThread.set(Thread.currentThread().getName());
            return Maybe.empty();
          };
      SequentialAgent sequence =
          SequentialAgent.builder()
              .name("sequence")
              .subAgents(
                  createSubAgent("writer", savedEvent()),
                  new TestBaseAgent(
                      "reader",
                      "description",
                      ImmutableList.of(recordThread),
                      ImmutableList.of(),
                      Flowable::empty))
              .build();
      Runner runner =
          Runner.builder()
              .app(App.builder().name(APP_NAME).rootAgent(sequence).build())
              .sessionService(appendDelayingSessionService())
              .scheduler(Schedulers.from(executor))
              .build();

      runRequestingLate(runner, RunConfig.builder().build()).assertComplete();

      assertThat(readerThread.get()).isEqualTo("invocation-scheduler");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void runAsync_eventAlreadyPersisted_nextSubAgentStartsWithoutSchedulerHop() {
    RecordingScheduler scheduler = new RecordingScheduler(Schedulers.trampoline());
    AtomicReference<@Nullable Object> seen = new AtomicReference<>(NOT_RUN);
    SequentialAgent sequence =
        SequentialAgent.builder()
            .name("sequence")
            .subAgents(createSubAgent("writer", savedEvent()), stateReader("k", seen))
            .build();
    Runner runner =
        Runner.builder()
            .app(App.builder().name(APP_NAME).rootAgent(sequence).build())
            .scheduler(scheduler)
            .build();

    runRequestingLate(runner, RunConfig.builder().build()).assertComplete();

    assertThat(seen.get()).isEqualTo("saved value");
    assertThat(scheduler.workersCreated()).isEqualTo(0);
  }

  /**
   * Runs a writer streaming {@code text} to state, then a state reader, with {@code resumability};
   * returns what the reader saw.
   */
  private static @Nullable Object outputKeySeenByNextSubAgent(
      String text, ResumabilityConfig resumability) {
    AtomicReference<@Nullable Object> seen = new AtomicReference<>(NOT_RUN);
    SequentialAgent sequence =
        SequentialAgent.builder()
            .name("sequence")
            .subAgents(streamingWriter("k", text), stateReader("k", seen))
            .build();
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name(APP_NAME)
                    .rootAgent(sequence)
                    .resumabilityConfig(resumability)
                    .build())
            .build();

    runRequestingLate(runner, SSE).assertComplete();
    return seen.get();
  }

  /**
   * Returns a SequentialAgent that runs a ParallelAgent, whose branches save "from a" to "a" and
   * "from b" to "b", then an aggregator that sends {@code aggregatorLlm} "Combine: {a} and {b}".
   */
  private static SequentialAgent fanOutThenAggregate(TestLlm aggregatorLlm) {
    ParallelAgent fanOut =
        ParallelAgent.builder()
            .name("fan_out")
            .subAgents(
                createTestAgentBuilder(createTestLlm(createTextLlmResponse("from a")))
                    .name("writer_a")
                    .outputKey("a")
                    .build(),
                createTestAgentBuilder(createTestLlm(createTextLlmResponse("from b")))
                    .name("writer_b")
                    .outputKey("b")
                    .build())
            .build();
    LlmAgent aggregator =
        createTestAgentBuilder(aggregatorLlm)
            .name("aggregator")
            .instruction("Combine: {a} and {b}")
            .build();
    return SequentialAgent.builder().name("sequence").subAgents(fanOut, aggregator).build();
  }

  /** Returns an in-memory session service whose {@code appendEvent} completes 50 ms later. */
  private static BaseSessionService appendDelayingSessionService() {
    InMemorySessionService delegate = new InMemorySessionService();
    BaseSessionService service = mock(BaseSessionService.class, delegatesTo(delegate));
    doAnswer(
            invocation ->
                Single.timer(50, MILLISECONDS)
                    .flatMap(
                        unused ->
                            delegate.appendEvent(
                                invocation.getArgument(0), invocation.getArgument(1))))
        .when(service)
        .appendEvent(any(), any());
    return service;
  }

  /** Returns an LLM agent that streams {@code text}, then saves it to {@code key}. */
  private static LlmAgent streamingWriter(String key, String text) {
    return createTestAgentBuilder(createTestLlm(createStreamingTextLlmResponses(text)))
        .name("writer")
        .outputKey(key)
        .build();
  }

  /** Returns an agent that records what session state holds under {@code key} when it starts. */
  private static TestBaseAgent stateReader(String key, AtomicReference<@Nullable Object> seen) {
    BeforeAgentCallback readState =
        callbackContext -> {
          seen.set(callbackContext.state().get(key));
          return Maybe.empty();
        };
    return new TestBaseAgent(
        "reader", "description", ImmutableList.of(readState), ImmutableList.of(), Flowable::empty);
  }

  /**
   * Runs a writer then a state reader under an agent that applies {@code transform}, with a 200 ms
   * bound on the wait, and checks that the run completes, the reader runs, and the run takes at
   * least the bound.
   */
  private static void assertSequenceStopsWaitingUnder(UnaryOperator<Flowable<Event>> transform) {
    Duration defaultTimeout = BaseAgent.persistWaitTimeout;
    BaseAgent.persistWaitTimeout = Duration.ofMillis(200);
    try {
      Event saved = savedEvent();
      AtomicReference<@Nullable Object> seen = new AtomicReference<>(NOT_RUN);
      SequentialAgent sequence =
          SequentialAgent.builder()
              .name("sequence")
              .subAgents(createSubAgent("writer", saved), stateReader("k", seen))
              .build();
      Runner runner =
          Runner.builder()
              .app(
                  App.builder()
                      .name(APP_NAME)
                      .rootAgent(new TransformingAgent(sequence, transform))
                      .build())
              .build();
      long start = System.nanoTime();

      runRequestingLate(runner, RunConfig.builder().build()).assertComplete();

      assertThat(seen.get()).isNotEqualTo(NOT_RUN);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isAtLeast(Duration.ofMillis(200));
    } finally {
      BaseAgent.persistWaitTimeout = defaultTimeout;
    }
  }

  /** Returns a "writer" event that saves "saved value" to "k". */
  private static Event savedEvent() {
    return createEvent("saved").toBuilder()
        .author("writer")
        .actions(EventActions.builder().stateDelta(ImmutableMap.of("k", "saved value")).build())
        .build();
  }

  /** An agent that passes its only sub-agent's events through {@code transform}. */
  private static final class TransformingAgent extends BaseAgent {
    private final UnaryOperator<Flowable<Event>> transform;

    TransformingAgent(BaseAgent subAgent, UnaryOperator<Flowable<Event>> transform) {
      super("wrapper", "description", ImmutableList.of(subAgent), null, null);
      this.transform = transform;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return transform.apply(subAgents().get(0).runAsync(invocationContext));
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }
}
