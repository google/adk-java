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
import static com.google.adk.testing.TestUtils.createSubAgent;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SequentialAgent}. */
@RunWith(JUnit4.class)
public final class SequentialAgentTest {

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

  // ---- Resumability: durable checkpoint resume (parity with Kotlin SequentialAgentTest). ----

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

  // Back-compat: a session paused before checkpoints existed must still fast-forward past
  // completed sub-agents (reconstructed from history) rather than re-running them.
  @Test
  @SuppressWarnings("deprecation") // Resumability flag is intentionally deprecated (partial).
  public void runAsync_resumeLegacySessionWithoutCheckpoints_doesNotRerunCompletedSubAgents() {
    TestBaseAgent agent1 =
        createSubAgent("agent1", createEvent("a1").toBuilder().author("agent1").build());
    TestBaseAgent agent2 =
        createSubAgent("agent2", createEvent("a2").toBuilder().author("agent2").build());
    TestBaseAgent agent3 =
        createSubAgent("agent3", createEvent("a3").toBuilder().author("agent3").build());
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seq").subAgents(agent1, agent2, agent3).build();

    // Simulate a legacy paused session: agent2 issued a long-running call (no checkpoint events),
    // and the user has now supplied the awaited function response.
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
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .build();
    // No populate/checkpoint state seeded -- this is the legacy case.

    var unused = sequentialAgent.runAsync(context).toList().blockingGet();

    // agent1 (already completed before the pause) must not re-run; agent2 and agent3 do.
    assertThat(agent1.getInvocationCount()).isEqualTo(0);
    assertThat(agent2.getInvocationCount()).isEqualTo(1);
    assertThat(agent3.getInvocationCount()).isEqualTo(1);
  }
}
