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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Correspondence.transforming;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.Model;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Unit tests for {@link Contents}. */
@RunWith(JUnit4.class)
public final class ContentsTest {

  private static final String USER = "user";
  private static final String AGENT = "agent";
  private static final String OTHER_AGENT = "other_agent";

  private static final Contents contentsProcessor = new Contents();
  private static final InMemorySessionService sessionService = new InMemorySessionService();

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
        createFunctionResponseEvent("fr1", "tool1", "call1", ImmutableMap.of("status", "pending"));
    Event frEvent2 =
        createFunctionResponseEvent("fr2", "tool1", "call1", ImmutableMap.of("status", "running"));
    Event frEvent3 =
        createFunctionResponseEvent("fr3", "tool1", "call1", ImmutableMap.of("status", "done"));
    ImmutableList<Event> inputEvents =
        ImmutableList.of(
            createUserEvent("u1", "Query"),
            fcEvent,
            createUserEvent("u2", "Wait"),
            frEvent1,
            createUserEvent("u3", "Done?"),
            frEvent2,
            frEvent3,
            createUserEvent("u4", "Follow up query"));

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).hasSize(6); // u1, fc1, merged_fr, u2, u3, u4
    assertThat(result.get(0)).isEqualTo(inputEvents.get(0).content().get());
    assertThat(result.get(1)).isEqualTo(inputEvents.get(1).content().get()); // Check fcEvent
    Content mergedContent = result.get(2);
    assertThat(mergedContent.parts().get()).hasSize(1);
    assertThat(mergedContent.parts().get().get(0).functionResponse().get().response().get())
        .containsExactly("status", "done"); // Last FR wins (frEvent3)
    assertThat(result.get(3)).isEqualTo(inputEvents.get(2).content().get()); // u2
    assertThat(result.get(4)).isEqualTo(inputEvents.get(4).content().get()); // u3
    assertThat(result.get(5)).isEqualTo(inputEvents.get(7).content().get()); // u4
  }

  @Test
  public void rearrangeHistory_multipleFRsForMultipleFC_returnsMergedFR() {
    Event fcEvent1 = createFunctionCallEvent("fc1", "tool1", "call1");
    Event fcEvent2 = createFunctionCallEvent("fc2", "tool1", "call2");

    Event frEvent1 =
        createFunctionResponseEvent("fr1", "tool1", "call1", ImmutableMap.of("status", "pending"));
    Event frEvent2 =
        createFunctionResponseEvent("fr2", "tool1", "call1", ImmutableMap.of("status", "done"));

    Event frEvent3 =
        createFunctionResponseEvent("fr3", "tool1", "call2", ImmutableMap.of("status", "pending"));
    Event frEvent4 =
        createFunctionResponseEvent("fr4", "tool1", "call2", ImmutableMap.of("status", "done"));

    ImmutableList<Event> inputEvents =
        ImmutableList.of(
            createUserEvent("u1", "I"),
            fcEvent1,
            createUserEvent("u2", "am"),
            frEvent1,
            createUserEvent("u3", "waiting"),
            frEvent2,
            createUserEvent("u4", "for"),
            fcEvent2,
            createUserEvent("u5", "you"),
            frEvent3,
            createUserEvent("u6", "to"),
            frEvent4,
            createUserEvent("u7", "Follow up query"));

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).hasSize(11); // u1, fc1, frEvent2, u2, u3, u4, fc2, frEvent4, u5, u6, u7
    assertThat(result.get(0)).isEqualTo(inputEvents.get(0).content().get()); // u1
    assertThat(result.get(1)).isEqualTo(inputEvents.get(1).content().get()); // fc1
    Content mergedContent = result.get(2);
    assertThat(mergedContent.parts().get()).hasSize(1);
    assertThat(mergedContent.parts().get().get(0).functionResponse().get().response().get())
        .containsExactly("status", "done"); // Last FR wins (frEvent2)
    assertThat(result.get(3)).isEqualTo(inputEvents.get(2).content().get()); // u2
    assertThat(result.get(4)).isEqualTo(inputEvents.get(4).content().get()); // u3
    assertThat(result.get(5)).isEqualTo(inputEvents.get(6).content().get()); // u4
    assertThat(result.get(6)).isEqualTo(inputEvents.get(7).content().get()); // fc2
    Content mergedContent2 = result.get(7);
    assertThat(mergedContent2.parts().get()).hasSize(1);
    assertThat(mergedContent2.parts().get().get(0).functionResponse().get().response().get())
        .containsExactly("status", "done"); // Last FR wins (frEvent4)
    assertThat(result.get(8)).isEqualTo(inputEvents.get(8).content().get()); // u5
    assertThat(result.get(9)).isEqualTo(inputEvents.get(10).content().get()); // u6
    assertThat(result.get(10)).isEqualTo(inputEvents.get(12).content().get()); // u7
  }

  @Test
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

  @Test
  public void processRequest_includeContentsNone_lastEventIsUser() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("u1", "Turn 1"),
            createAgentEvent("a1", "Reply 1"),
            createUserEvent("u2", "Turn 2"));
    List<Content> result =
        runContentsProcessorWithIncludeContents(events, LlmAgent.IncludeContents.NONE);
    assertThat(result).containsExactly(events.get(2).content().get());
  }

  @Test
  public void processRequest_includeContentsNone_lastEventIsOtherAgent() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("u1", "Turn 1"),
            createAgentEvent("a1", "Reply 1"),
            createAgentEvent(OTHER_AGENT, "oa1", "Other Agent Turn"));
    List<Content> result =
        runContentsProcessorWithIncludeContents(events, LlmAgent.IncludeContents.NONE);
    assertThat(result)
        .containsExactly(
            Content.fromParts(
                Part.fromText("For context:"),
                Part.fromText("[other_agent] said: Other Agent Turn")));
  }

  @Test
  public void processRequest_includeContentsNone_noUserMessage() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createAgentEvent("a1", "Reply 1"),
            createFunctionCallEvent("fc1", "tool1", "call1"),
            createFunctionResponseEvent("fr1", "tool1", "call1"));
    List<Content> result =
        runContentsProcessorWithIncludeContents(events, LlmAgent.IncludeContents.NONE);
    assertThat(result).isEmpty();
  }

  @Test
  public void processRequest_includeContentsNone_asyncFRAcrossTurns_throwsException() {
    Event u1 = createUserEvent("u1", "Query 1");
    Event fc1 = createFunctionCallEvent("fc1", "tool1", "call1");
    Event u2 = createUserEvent("u2", "Query 2");
    Event fr1 = createFunctionResponseEvent("fr1", "tool1", "call1"); // FR for fc1

    ImmutableList<Event> events = ImmutableList.of(u1, fc1, u2, fr1);

    // The current turn starts from u2. fc1 is not in the sublist [u2, fr1], so rearrangement fails.
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> runContentsProcessorWithIncludeContents(events, LlmAgent.IncludeContents.NONE));
    assertThat(e)
        .hasMessageThat()
        .contains("No function call event found for function response IDs: [call1]");
  }

  @Test
  public void processRequest_includeContentsNone_asyncFRWithinTurn() {
    Event u1 = createUserEvent("u1", "Query 1");
    Event fc1 = createFunctionCallEvent("fc1", "tool1", "call1");
    Event a1 = createAgentEvent("a1", "Agent thinking");
    Event fr1 = createFunctionResponseEvent("fr1", "tool1", "call1");

    ImmutableList<Event> events = ImmutableList.of(u1, fc1, a1, fr1);
    // Current turn starts with u1. The list passed to getContents is [u1, fc1, a1, fr1].
    List<Content> result =
        runContentsProcessorWithIncludeContents(events, LlmAgent.IncludeContents.NONE);
    assertThat(result)
        .containsExactly(
            events.get(0).content().get(), // u1
            events.get(1).content().get(), // fc1
            events.get(3).content().get()) // fr1 (merged)
        .inOrder();
  }

  @Test
  public void processRequest_sequentialFCFR_returnsOriginalList() {
    Event e1 = createUserEvent("e1", "Not important");
    Event e2 =
        createAgentEventWithTextAndFunctionCall(
            AGENT, "e2", "some text", "tool1", "call1", ImmutableMap.of("request", "foo"));
    Event e3 =
        createFunctionResponseEvent(
            AGENT, "e3", "tool1", "call1", ImmutableMap.of("response", "bar"));
    Event e4 =
        createAgentEventWithTextAndFunctionCall(
            AGENT, "e4", "some other text", "tool2", "call2", ImmutableMap.of("request", "X"));
    Event e5 =
        createFunctionResponseEvent(
            AGENT, "e5", "tool2", "call2", ImmutableMap.of("response", "Y"));
    ImmutableList<Event> inputEvents = ImmutableList.of(e1, e2, e3, e4, e5);

    List<Content> result = runContentsProcessor(inputEvents);

    assertThat(result).isEqualTo(eventsToContents(inputEvents));
  }

  @Test
  public void rearrangeHistory_gemini3interleavedFCFR_groupsFcAndFr() {
    Event u1 = createUserEvent("u1", "Query");
    Event fc1 = createFunctionCallEvent("fc1", "tool1", "call1");
    Event fr1 = createFunctionResponseEvent("fr1", "tool1", "call1");
    Event fc2 = createFunctionCallEvent("fc2", "tool2", "call2");
    Event fr2 = createFunctionResponseEvent("fr2", "tool2", "call2");

    ImmutableList<Event> inputEvents = ImmutableList.of(u1, fc1, fr1, fc2, fr2);

    List<Content> result = runContentsProcessorWithModelName(inputEvents, "gemini-3-flash-exp");

    assertThat(result).hasSize(4);
    assertThat(result.get(0)).isEqualTo(u1.content().get());
    assertThat(result.get(1)).isEqualTo(fc1.content().get());
    assertThat(result.get(2)).isEqualTo(fc2.content().get());
    Content mergedContent = result.get(3);
    assertThat(mergedContent.parts().get()).hasSize(2);
    assertThat(mergedContent.parts().get().get(0).functionResponse().get().name())
        .hasValue("tool1");
    assertThat(mergedContent.parts().get().get(1).functionResponse().get().name())
        .hasValue("tool2");
  }

  @Test
  public void processRequest_singleCompaction() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("env1", "content 1", "inv1", 1),
            createUserEvent("env2", "content 2", "inv2", 2),
            createCompactedEvent(1, 2, "Summary 1-2"),
            createUserEvent("env3", "content 3", "inv3", 3));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("Summary 1-2", "content 3");
  }

  @Test
  public void processRequest_startsWithCompaction() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createCompactedEvent(1, 2, "Summary 1-2"),
            createUserEvent("env3", "content 3", "inv3", 3),
            createUserEvent("env4", "content 4", "inv4", 4));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("Summary 1-2", "content 3", "content 4");
  }

  @Test
  public void processRequest_endsWithCompaction() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("env1", "content 1", "inv1", 1),
            createUserEvent("env2", "content 2", "inv2", 2),
            createUserEvent("env3", "content 3", "inv3", 2),
            createCompactedEvent(2, 3, "Summary 2-3"));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("content 1", "Summary 2-3");
  }

  @Test
  public void processRequest_multipleCompactions() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("env1", "content 1", "inv1", 1),
            createUserEvent("env2", "content 2", "inv2", 2),
            createUserEvent("env3", "content 3", "inv3", 3),
            createUserEvent("env4", "content 4", "inv4", 4),
            createCompactedEvent(1, 4, "Summary 1-4"),
            createUserEvent("env5", "content 5", "inv5", 5),
            createUserEvent("env6", "content 6", "inv6", 6),
            createUserEvent("env7-1", "content 7-1", "inv7", 7),
            createUserEvent("env7-2", "content 7-2", "inv8", 8),
            createUserEvent("env9", "content 9", "inv9", 9),
            createCompactedEvent(6, 9, "Summary 6-9"),
            createUserEvent("env10", "content 10", "inv10", 10));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("Summary 1-4", "content 5", "Summary 6-9", "content 10");
  }

  @Test
  public void processRequest_compactionWithUncompactedEventsBetween() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("e1", "content 1", "inv1", 1),
            createUserEvent("e2", "content 2", "inv2", 2),
            createUserEvent("e3", "content 3", "inv3", 3),
            createCompactedEvent(1, 2, "Summary 1-2"));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("content 3", "Summary 1-2");
  }

  @Test
  public void processRequest_rollingSummary_removesRedundancy() {
    // Scenario: Rolling summary where a later summary covers a superset of the time range.
    // Input: [E1(1), C1(Cover 1-1), E3(3), C2(Cover 1-3)]
    // Expected: [C2]
    // Explanation: C2 covers the range [1, 3], which includes the range covered by C1 [1, 1].
    // Therefore, C1 is redundant. E1 and E3 are also covered by C2.
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("e1", "E1", "inv1", 1),
            createCompactedEvent(1, 1, "C1"),
            createUserEvent("e3", "E3", "inv3", 3),
            createCompactedEvent(1, 3, "C2"));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("C2");
  }

  @Test
  public void processRequest_rollingSummaryWithRetention() {
    // Input: with retention size 3: [E1, E2, E3, E4, C1(Cover 1-1), E6, E7, C2(Cover 1-3), E9]
    // Expected: [C2, E4, E6, E7, E9]
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("e1", "E1", "inv1", 1),
            createUserEvent("e2", "E2", "inv2", 2),
            createUserEvent("e3", "E3", "inv3", 3),
            createUserEvent("e4", "E4", "inv4", 4),
            createCompactedEvent(1, 1, "C1"),
            createUserEvent("e6", "E6", "inv6", 6),
            createUserEvent("e7", "E7", "inv7", 7),
            createCompactedEvent(1, 3, "C2"),
            createUserEvent("e9", "E9", "inv9", 9));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("C2", "E4", "E6", "E7", "E9");
  }

  @Test
  public void processRequest_rollingSummary_preservesUncoveredHistory() {
    // Input: [E1(1), E2(2), E3(3), E4(4), C1(2-2), E6(6), E7(7), C2(2-3), E9(9)]
    // Expected: [E1, C2, E4, E6, E7, E9]
    // E1 is before C1/C2 range, so it is preserved.
    // C1 (2-2) is covered by C2 (2-3), so C1 is removed.
    // E2, E3 are covered by C2.
    // E4, E6, E7, E9 are retained.
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("e1", "E1", "inv1", 1),
            createUserEvent("e2", "E2", "inv2", 2),
            createUserEvent("e3", "E3", "inv3", 3),
            createUserEvent("e4", "E4", "inv4", 4),
            createCompactedEvent(2, 2, "C1"),
            createUserEvent("e6", "E6", "inv6", 6),
            createUserEvent("e7", "E7", "inv7", 7),
            createCompactedEvent(2, 3, "C2"),
            createUserEvent("e9", "E9", "inv9", 9));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("E1", "C2", "E4", "E6", "E7", "E9");
  }

  @Test
  public void processRequest_slidingWindow_preservesOverlappingCompactions() {
    // Case 1: Sliding Window + Retention
    // Input: [E1(1), E2(2), E3(3), C1(1-2), E4(5), C2(2-3), E5(7)]
    // Overlap: C1 and C2 overlap at 2. C1 is NOT redundant (start 1 < start 2).
    // Expected: [C1, C2, E4, E5]
    // E1(1) covered by C1.
    // E2(2) covered by C1 (and C2).
    // E3(3) covered by C2.
    // E4(5) retained.
    // E5(7) retained.
    ImmutableList<Event> events =
        ImmutableList.of(
            createUserEvent("e1", "E1", "inv1", 1),
            createUserEvent("e2", "E2", "inv2", 2),
            createUserEvent("e3", "E3", "inv3", 3),
            createCompactedEvent(1, 2, "C1"),
            createUserEvent("e4", "E4", "inv4", 5),
            createCompactedEvent(2, 3, "C2"),
            createUserEvent("e5", "E5", "inv5", 7));

    List<Content> contents = runContentsProcessor(events);
    assertThat(contents)
        .comparingElementsUsing(
            transforming((Content c) -> c.parts().get().get(0).text().get(), "content text"))
        .containsExactly("C1", "C2", "E4", "E5");
  }

  private static Event createUserEvent(String id, String text) {
    return Event.builder()
        .id(id)
        .author(USER)
        .content(Optional.of(Content.fromParts(Part.fromText(text))))
        .invocationId("invocationId")
        .build();
  }

  private static Event createUserEvent(
      String id, String text, String invocationId, long timestamp) {
    return Event.builder()
        .id(id)
        .author(USER)
        .content(Optional.of(Content.fromParts(Part.fromText(text))))
        .invocationId(invocationId)
        .timestamp(timestamp)
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
    return runContentsProcessorWithIncludeContents(events, LlmAgent.IncludeContents.DEFAULT);
  }

  private List<Content> runContentsProcessorWithIncludeContents(
      List<Event> events, LlmAgent.IncludeContents includeContents) {
    LlmAgent agent = LlmAgent.builder().name(AGENT).includeContents(includeContents).build();
    Session session =
        Session.builder("test-session")
            .appName("test-app")
            .userId("test-user")
            .events(new ArrayList<>(events))
            .build();
    InvocationContext context =
        InvocationContext.builder()
            .invocationId("test-invocation")
            .agent(agent)
            .session(session)
            .sessionService(sessionService)
            .build();

    LlmRequest initialRequest = LlmRequest.builder().build();
    RequestProcessor.RequestProcessingResult result =
        contentsProcessor.processRequest(context, initialRequest).blockingGet();
    return result.updatedRequest().contents();
  }

  private List<Content> runContentsProcessorWithModelName(List<Event> events, String modelName) {
    LlmAgent agent =
        Mockito.spy(
            LlmAgent.builder()
                .name(AGENT)
                .includeContents(LlmAgent.IncludeContents.DEFAULT)
                .build());
    Model model = Model.builder().modelName(modelName).build();
    Mockito.doReturn(model).when(agent).resolvedModel();

    Session session =
        Session.builder("test-session")
            .appName("test-app")
            .userId("test-user")
            .events(new ArrayList<>(events))
            .build();
    InvocationContext context =
        InvocationContext.builder()
            .invocationId("test-invocation")
            .agent(agent)
            .session(session)
            .sessionService(sessionService)
            .build();

    LlmRequest initialRequest = LlmRequest.builder().build();
    RequestProcessor.RequestProcessingResult result =
        contentsProcessor.processRequest(context, initialRequest).blockingGet();
    return result.updatedRequest().contents();
  }

  private static ImmutableList<Content> eventsToContents(List<Event> events) {
    return events.stream()
        .map(Event::content)
        .filter(Objects::nonNull)
        .map(Optional::get)
        .collect(toImmutableList());
  }

  private Event createCompactedEvent(long startTimestamp, long endTimestamp, String content) {
    return Event.builder()
        .actions(
            EventActions.builder()
                .compaction(
                    EventCompaction.builder()
                        .startTimestamp(startTimestamp)
                        .endTimestamp(endTimestamp)
                        .compactedContent(
                            Content.builder()
                                .role("model")
                                .parts(Part.builder().text(content).build())
                                .build())
                        .build())
                .build())
        .build();
  }
}
