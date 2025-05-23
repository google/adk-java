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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Contents}. */
@RunWith(JUnit4.class)
public final class ContentsTest {

  private static final String USER = "user";
  private static final String AGENT = "agent";
  private static final String OTHER_AGENT = "other_agent";

  private final Contents contentsProcessor = new Contents();

  @Test
  public void rearrangeLatest_emptyList_returnsEmptyList() {
    List<Content> result = runContentsProcessor(ImmutableList.of());
    assertThat(result).isEmpty();
  }

  @Test
  public void rearrangeLatest_noFunctionResponseAtEnd_returnsOriginalList() {
    ImmutableList<Event> events =
        ImmutableList.of(createUserEvent("e1", "Hello"), createAgentEvent("e2", "Hi there"));
    List<Content> result = runContentsProcessor(events);
    assertThat(result).isEqualTo(eventsToContents(events));
  }

  @Test
  public void rearrangeLatest_simpleMatchedFR_returnsOriginalList() {
    Event fcEvent = createFunctionCallEvent("fc1", "tool1", "call1");
    Event frEvent = createFunctionResponseEvent("fr1", "tool1", "call1");
    ImmutableList<Event> events =
        ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, frEvent);
    List<Content> result = runContentsProcessor(events);
    assertThat(result).isEqualTo(eventsToContents(events));
  }

  @Test
  public void rearrangeLatest_asyncFRSimple_returnsRearrangedList() {
    Event fcEvent = createFunctionCallEvent("fc1", "tool1", "call1");
    Event userEvent = createUserEvent("u2", "Something else");
    Event frEvent = createFunctionResponseEvent("fr1", "tool1", "call1");
    ImmutableList<Event> inputEvents =
        ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, userEvent, frEvent);
    ImmutableList<Content> expected =
        eventsToContents(ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, frEvent));

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void rearrangeLatest_asyncFRMultipleIntermediate_returnsRearrangedList() {
    Event fcEvent = createFunctionCallEvent("fc1", "tool1", "call1");
    Event modelEvent1 = createAgentEvent("m1", "Thinking...");
    Event userEvent = createUserEvent("u2", "More input");
    Event modelEvent2 = createAgentEvent("m2", "Still thinking...");
    Event frEvent = createFunctionResponseEvent("fr1", "tool1", "call1");
    ImmutableList<Event> inputEvents =
        ImmutableList.of(
            createUserEvent("u1", "Query"), fcEvent, modelEvent1, userEvent, modelEvent2, frEvent);
    ImmutableList<Content> expected =
        eventsToContents(ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, frEvent));

    List<Content> result = runContentsProcessor(inputEvents);
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void rearrangeLatest_multipleFRsForSameFCAsync_returnsMergedFR() {
    Event fcEvent = createFunctionCallEvent("fc1", "tool1", "call1");
    Event frEvent1 =
        createFunctionResponseEvent("fr1", "tool1", "call1", ImmutableMap.of("status", "running"));
    Event frEvent2 =
        createFunctionResponseEvent("fr2", "tool1", "call1", ImmutableMap.of("status", "done"));
    ImmutableList<Event> inputEvents =
        ImmutableList.of(
            createUserEvent("u1", "Query"),
            fcEvent,
            createUserEvent("u2", "Wait"),
            frEvent1,
            createUserEvent("u3", "Done?"),
            frEvent2);

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).hasSize(3);
    assertThat(result.get(0)).isEqualTo(inputEvents.get(0).content().get());
    assertThat(result.get(1)).isEqualTo(inputEvents.get(1).content().get()); // Check merged event
    Content mergedContent = result.get(2);
    assertThat(mergedContent.parts().get()).hasSize(1);
    assertThat(mergedContent.parts().get().get(0).functionResponse().get().response().get())
        .containsExactly("status", "done"); // Last FR wins
  }

  @Test
  public void rearrangeLatest_missingFCEvent_throwsException() {
    Event frEvent = createFunctionResponseEvent("fr1", "tool1", "call1");
    ImmutableList<Event> events = ImmutableList.of(createUserEvent("u1", "Query"), frEvent);

    assertThrows(IllegalStateException.class, () -> runContentsProcessor(events));
  }

  @Test
  public void rearrangeLatest_parallelFCsAsyncFR_returnsRearrangedList() {
    Event fcEvent = createParallelFunctionCallEvent("fc1", "tool1", "call1", "tool2", "call2");
    Event userEvent = createUserEvent("u2", "Wait");
    Event frEvent1 = createFunctionResponseEvent("fr1", "tool1", "call1");
    ImmutableList<Event> inputEvents =
        ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, userEvent, frEvent1);
    ImmutableList<Content> expected =
        eventsToContents(ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, frEvent1));
    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void rearrangeHistory_emptyList_returnsEmptyList() {
    List<Content> result = runContentsProcessor(ImmutableList.of());
    assertThat(result).isEmpty();
  }

  @Test
  public void rearrangeHistory_noFCFR_returnsOriginalList() {
    ImmutableList<Event> events =
        ImmutableList.of(createUserEvent("e1", "Hello"), createAgentEvent("e2", "Hi there"));
    List<Content> result = runContentsProcessor(events);
    assertThat(result).isEqualTo(eventsToContents(events));
  }

  @Test
  public void rearrangeHistory_simpleMatchedFCFR_returnsOriginalList() {
    Event fcEvent = createFunctionCallEvent("fc1", "tool1", "call1");
    Event frEvent = createFunctionResponseEvent("fr1", "tool1", "call1");
    ImmutableList<Event> events =
        ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, frEvent);
    List<Content> result = runContentsProcessor(events);
    assertThat(result).isEqualTo(eventsToContents(events));
  }

  @Test
  public void rearrangeHistory_asyncFR_returnsRearrangedList() {
    Event fcEvent = createFunctionCallEvent("fc1", "tool1", "call1");
    Event userEvent = createUserEvent("u2", "Something else");
    Event frEvent = createFunctionResponseEvent("fr1", "tool1", "call1");
    ImmutableList<Event> inputEvents =
        ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, userEvent, frEvent);
    ImmutableList<Content> expected =
        eventsToContents(ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, frEvent));

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void rearrangeHistory_multipleFRsForSameFC_returnsMergedFR() {
    Event fcEvent = createFunctionCallEvent("fc1", "tool1", "call1");
    Event frEvent1 =
        createFunctionResponseEvent("fr1", "tool1", "call1", ImmutableMap.of("status", "running"));
    Event frEvent2 =
        createFunctionResponseEvent("fr2", "tool1", "call1", ImmutableMap.of("status", "done"));
    ImmutableList<Event> inputEvents =
        ImmutableList.of(
            createUserEvent("u1", "Query"),
            fcEvent,
            createUserEvent("u2", "Wait"),
            frEvent1,
            createUserEvent("u3", "Done?"),
            frEvent2);

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).hasSize(3); // u1, fc1, merged_fr
    assertThat(result.get(0)).isEqualTo(inputEvents.get(0).content().get());
    assertThat(result.get(1)).isEqualTo(inputEvents.get(1).content().get()); // Check merged event
    Content mergedContent = result.get(2);
    assertThat(mergedContent.parts().get()).hasSize(1);
    assertThat(mergedContent.parts().get().get(0).functionResponse().get().response().get())
        .containsExactly("status", "done"); // Last FR wins
  }

  @Test
  @Ignore("TODO: b/412663475 - Handle parallel function calls within the same event.")
  public void rearrangeHistory_parallelFCsSequentialFRs_returnsMergedFR() {
    Event fcEvent = createParallelFunctionCallEvent("fc1", "tool1", "call1", "tool2", "call2");
    Event frEvent1 = createFunctionResponseEvent("fr1", "tool1", "call1");
    Event frEvent2 = createFunctionResponseEvent("fr2", "tool2", "call2");
    ImmutableList<Event> inputEvents =
        ImmutableList.of(createUserEvent("u1", "Query"), fcEvent, frEvent1, frEvent2);

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).hasSize(3); // u1, fc1, merged_fr
    assertThat(result.get(0)).isEqualTo(inputEvents.get(0).content().get());
    assertThat(result.get(1)).isEqualTo(inputEvents.get(1).content().get()); // Check merged event
    Content mergedContent = result.get(2);
    assertThat(mergedContent.parts().get()).hasSize(2);
    assertThat(mergedContent.parts().get().get(0).functionResponse().get().name())
        .hasValue("tool1");
    assertThat(mergedContent.parts().get().get(1).functionResponse().get().name())
        .hasValue("tool2");
  }

  @Test
  @Ignore("TODO: b/412663475 - Handle parallel function calls within the same event.")
  public void rearrangeHistory_parallelFCsAsyncFRs_returnsMergedFR() {
    Event fcEvent = createParallelFunctionCallEvent("fc1", "tool1", "call1", "tool2", "call2");
    Event userEvent1 = createUserEvent("u2", "Wait");
    Event frEvent1 = createFunctionResponseEvent("fr1", "tool1", "call1");
    Event userEvent2 = createUserEvent("u3", "More wait");
    Event frEvent2 = createFunctionResponseEvent("fr2", "tool2", "call2");
    ImmutableList<Event> inputEvents =
        ImmutableList.of(
            createUserEvent("u1", "Query"), fcEvent, userEvent1, frEvent1, userEvent2, frEvent2);

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).hasSize(3); // u1, fc1, merged_fr
    assertThat(result.get(0)).isEqualTo(inputEvents.get(0).content().get());
    assertThat(result.get(1)).isEqualTo(inputEvents.get(1).content().get()); // Check merged event
    Content mergedContent = result.get(2);
    assertThat(mergedContent.parts().get()).hasSize(2);
    assertThat(mergedContent.parts().get().get(0).functionResponse().get().name())
        .hasValue("tool1");
    assertThat(mergedContent.parts().get().get(1).functionResponse().get().name())
        .hasValue("tool2");
  }

  @Test
  public void rearrangeHistory_missingFR_doesNotThrow() {
    Event fcEvent1 = createFunctionCallEvent("fc1", "tool1", "call1");
    Event userEvent = createUserEvent("u2", "Input");
    Event fcEvent2 = createFunctionCallEvent("fc2", "tool2", "call2");
    Event frEvent2 =
        createFunctionResponseEvent("fr2", "tool2", "call2"); // FC1 has no corresponding FR
    ImmutableList<Event> inputEvents =
        ImmutableList.of(createUserEvent("u1", "Query"), fcEvent1, userEvent, fcEvent2, frEvent2);
    ImmutableList<Content> expected = eventsToContents(inputEvents);

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void rearrangeHistory_interleavedFCFR_returnsCorrectOrder() {
    Event fcEvent1 = createFunctionCallEvent("fc1", "tool1", "call1");
    Event frEvent1 = createFunctionResponseEvent("fr1", "tool1", "call1");
    Event userEvent = createUserEvent("u2", "Input");
    Event fcEvent2 = createFunctionCallEvent("fc2", "tool2", "call2");
    Event frEvent2 = createFunctionResponseEvent("fr2", "tool2", "call2");
    ImmutableList<Event> inputEvents =
        ImmutableList.of(
            createUserEvent("u1", "Query"), fcEvent1, frEvent1, userEvent, fcEvent2, frEvent2);
    ImmutableList<Content> expected =
        eventsToContents(
            ImmutableList.of(
                createUserEvent("u1", "Query"), fcEvent1, frEvent1, userEvent, fcEvent2, frEvent2));
    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void rearrangeHistory_interleavedAsyncFCFR_returnsCorrectOrder() {
    Event u1 = createUserEvent("u1", "Query 1");
    Event fc1 = createFunctionCallEvent("fc1", "tool1", "call1");
    Event u2 = createUserEvent("u2", "Query 2");
    Event fc2 = createFunctionCallEvent("fc2", "tool2", "call2");
    Event u3 = createUserEvent("u3", "Intermediate");
    Event fr1 = createFunctionResponseEvent("fr1", "tool1", "call1");
    Event u4 = createUserEvent("u4", "More intermediate");
    Event fr2 = createFunctionResponseEvent("fr2", "tool2", "call2");

    ImmutableList<Event> inputEvents = ImmutableList.of(u1, fc1, u2, fc2, u3, fr1, u4, fr2);
    ImmutableList<Content> expected = eventsToContents(ImmutableList.of(u1, fc1, u2, fc2, fr2));

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void convertForeignEvent_eventsFromOtherAgents_returnsContextualOnlyEvents() {
    Event u1 = createUserEvent("u1", "Query 1");
    Event o1 =
        createAgentEventWithTextAndFunctionCall(
            OTHER_AGENT,
            "o1",
            "Some text",
            "tool1",
            "call1",
            ImmutableMap.of("arg1", "value", "arg2", ImmutableList.of(1, 2)));
    Event fr1 =
        createFunctionResponseEvent(
            OTHER_AGENT, "fr1", "tool1", "call1", ImmutableMap.of("result", "ok"));
    Event a1 =
        createAgentEventWithTextAndFunctionCall(
            AGENT, "a1", "Some other response", "tool2", "call2", ImmutableMap.of("arg", "foo"));
    Event fr2 =
        createFunctionResponseEvent(
            AGENT, "fr2", "tool2", "call2", ImmutableMap.of("result", "bar"));
    ImmutableList<Event> inputEvents = ImmutableList.of(u1, o1, fr1, a1, fr2);

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result)
        .containsExactly(
            u1.content().get(),
            Content.fromParts(
                Part.fromText("For context:"),
                Part.fromText("[other_agent] said: Some text"),
                Part.fromText(
                    "[other_agent] called tool `tool1` with parameters: "
                        + "{\"arg1\":\"value\",\"arg2\":[1,2]}")),
            Content.fromParts(
                Part.fromText("For context:"),
                Part.fromText("[other_agent] `tool1` tool returned result: {\"result\":\"ok\"}")),
            a1.content().get(),
            fr2.content().get())
        .inOrder();
  }

  private static Event createUserEvent(String id, String text) {
    return Event.builder()
        .id(id)
        .author(USER)
        .content(Optional.of(Content.fromParts(Part.fromText(text))))
        .invocationId("invocationId")
        .build();
  }

  private static Event createAgentEvent(String id, String text) {
    return createAgentEvent(AGENT, id, text);
  }

  private static Event createAgentEvent(String agent, String id, String text) {
    return Event.builder()
        .id(id)
        .author(agent)
        .content(
            Content.builder().role("model").parts(ImmutableList.of(Part.fromText(text))).build())
        .invocationId("invocationId")
        .build();
  }

  private static Event createFunctionCallEvent(String id, String toolName, String callId) {
    return createFunctionCallEvent(AGENT, id, toolName, callId);
  }

  private static Event createFunctionCallEvent(
      String agent, String id, String toolName, String callId) {
    return Event.builder()
        .id(id)
        .author(agent)
        .content(
            Content.builder()
                .role("model")
                .parts(
                    ImmutableList.of(
                        Part.builder()
                            .functionCall(FunctionCall.builder().name(toolName).id(callId).build())
                            .build()))
                .build())
        .invocationId("invocationId")
        .build();
  }

  private static Event createAgentEventWithTextAndFunctionCall(
      String agent,
      String id,
      String text,
      String toolName,
      String callId,
      Map<String, Object> args) {
    return Event.builder()
        .id(id)
        .author(agent)
        .content(
            Content.builder()
                .role("model")
                .parts(
                    ImmutableList.of(
                        Part.fromText(text),
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder().name(toolName).id(callId).args(args).build())
                            .build()))
                .build())
        .invocationId("invocationId")
        .build();
  }

  private static Event createParallelFunctionCallEvent(
      String id, String toolName1, String callId1, String toolName2, String callId2) {
    return Event.builder()
        .id(id)
        .author(AGENT)
        .content(
            Content.builder()
                .role("model")
                .parts(
                    ImmutableList.of(
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder().name(toolName1).id(callId1).build())
                            .build(),
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder().name(toolName2).id(callId2).build())
                            .build()))
                .build())
        .invocationId("invocationId")
        .build();
  }

  private static Event createFunctionResponseEvent(String id, String toolName, String callId) {
    return createFunctionResponseEvent(id, toolName, callId, ImmutableMap.of("result", "ok"));
  }

  private static Event createFunctionResponseEvent(
      String id, String toolName, String callId, Map<String, Object> response) {
    return Event.builder()
        .id(id)
        .author(AGENT)
        .invocationId("invocationId")
        .content(
            Content.fromParts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .name(toolName)
                            .id(callId)
                            .response(response)
                            .build())
                    .build()))
        .build();
  }

  private static Event createFunctionResponseEvent(
      String agent, String id, String toolName, String callId, Map<String, Object> response) {
    return Event.builder()
        .id(id)
        .author(agent)
        .invocationId("invocationId")
        .content(
            Content.fromParts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .name(toolName)
                            .id(callId)
                            .response(response)
                            .build())
                    .build()))
        .build();
  }

  private List<Content> runContentsProcessor(List<Event> events) {
    LlmAgent agent = LlmAgent.builder().name(AGENT).build();
    Session session =
        Session.builder("test-session")
            .appName("test-app")
            .userId("test-user")
            .events(new ArrayList<>(events))
            .build();
    InvocationContext context =
        InvocationContext.create(
            new InMemorySessionService(),
            new InMemoryArtifactService(),
            "test-invocation",
            agent,
            session,
            /* userContent= */ null,
            RunConfig.builder().build());

    LlmRequest initialRequest = LlmRequest.builder().build();
    RequestProcessor.RequestProcessingResult result =
        contentsProcessor.processRequest(context, initialRequest).blockingGet();
    return result.updatedRequest().contents();
  }

  private static ImmutableList<Content> eventsToContents(List<Event> events) {
    return ImmutableList.copyOf(
        events.stream()
            .map(Event::content)
            .filter(Objects::nonNull)
            .map(Optional::get)
            .collect(Collectors.toList()));
  }
}
