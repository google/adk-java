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
import static com.google.adk.testing.TestUtils.createSubAgent;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
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
}
