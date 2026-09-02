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

package com.google.adk.events;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventActionsTest {

  private static final Part PART = Part.builder().text("text").build();
  private static final Content CONTENT = Content.builder().parts(PART).build();
  private static final ToolConfirmation TOOL_CONFIRMATION =
      ToolConfirmation.builder().hint("hint").confirmed(true).build();
  private static final EventCompaction COMPACTION =
      EventCompaction.builder()
          .startTimestamp(123L)
          .endTimestamp(456L)
          .compactedContent(CONTENT)
          .build();

  @Test
  public void toBuilder_createsBuilderWithSameValues() {
    EventActions eventActionsWithSkipSummarization =
        EventActions.builder()
            .skipSummarization(true)
            .compaction(COMPACTION)
            .deletedArtifactIds(ImmutableSet.of("d1"))
            .build();

    EventActions eventActionsAfterRebuild = eventActionsWithSkipSummarization.toBuilder().build();

    assertThat(eventActionsAfterRebuild).isEqualTo(eventActionsWithSkipSummarization);
    assertThat(eventActionsAfterRebuild.compaction()).hasValue(COMPACTION);
  }

  @Test
  public void merge_mergesAllFields() {
    EventActions eventActions1 =
        EventActions.builder()
            .skipSummarization(true)
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key1", "value1")))
            .artifactDelta(new ConcurrentHashMap<>(ImmutableMap.of("artifact1", 1)))
            .deletedArtifactIds(ImmutableSet.of("deleted1"))
            .requestedAuthConfigs(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("config1", new ConcurrentHashMap<>(ImmutableMap.of("k", "v")))))
            .requestedToolConfirmations(
                new ConcurrentHashMap<>(ImmutableMap.of("tool1", TOOL_CONFIRMATION)))
            .compaction(COMPACTION)
            .build();
    EventActions eventActions2 =
        EventActions.builder()
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key2", "value2")))
            .artifactDelta(new ConcurrentHashMap<>(ImmutableMap.of("artifact2", 2)))
            .deletedArtifactIds(ImmutableSet.of("deleted2"))
            .transferToAgent("agentId")
            .escalate(true)
            .requestedAuthConfigs(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("config2", new ConcurrentHashMap<>(ImmutableMap.of("k", "v")))))
            .requestedToolConfirmations(
                new ConcurrentHashMap<>(ImmutableMap.of("tool2", TOOL_CONFIRMATION)))
            .endOfAgent(true)
            .build();

    EventActions merged = eventActions1.toBuilder().merge(eventActions2).build();

    assertThat(merged.skipSummarization()).hasValue(true);
    assertThat(merged.stateDelta()).containsExactly("key1", "value1", "key2", "value2");
    assertThat(merged.artifactDelta()).containsExactly("artifact1", 1, "artifact2", 2);
    assertThat(merged.deletedArtifactIds()).containsExactly("deleted1", "deleted2");
    assertThat(merged.transferToAgent()).hasValue("agentId");
    assertThat(merged.escalate()).hasValue(true);
    assertThat(merged.requestedAuthConfigs())
        .containsExactly(
            "config1",
            new ConcurrentHashMap<>(ImmutableMap.of("k", "v")),
            "config2",
            new ConcurrentHashMap<>(ImmutableMap.of("k", "v")));
    assertThat(merged.requestedToolConfirmations())
        .containsExactly("tool1", TOOL_CONFIRMATION, "tool2", TOOL_CONFIRMATION);
    assertThat(merged.endOfAgent()).isTrue();
    assertThat(merged.compaction()).hasValue(COMPACTION);
  }

  @Test
  public void agentState_roundTripsThroughToBuilder() {
    EventActions actions =
        EventActions.builder().agentState(ImmutableMap.of("current_sub_agent", "b")).build();

    EventActions rebuilt = actions.toBuilder().build();

    assertThat(rebuilt).isEqualTo(actions);
    assertThat(rebuilt.agentState()).hasValue(ImmutableMap.of("current_sub_agent", "b"));
  }

  @Test
  public void agentState_roundTripsThroughJson() {
    EventActions actions =
        EventActions.builder().agentState(ImmutableMap.of("times_looped", 2)).build();

    EventActions deserialized = EventActions.fromJsonString(actions.toJson(), EventActions.class);

    assertThat(deserialized.agentState()).isPresent();
    assertThat(deserialized.agentState().get()).containsEntry("times_looped", 2);
  }

  @Test
  public void agentState_absentByDefault_andOmittedFromJson() {
    EventActions actions = EventActions.builder().build();

    assertThat(actions.agentState()).isEmpty();
    // Kept out of the serialized form so pre-existing events stay byte-identical.
    assertThat(actions.toJson()).doesNotContain("agentState");
  }

  @Test
  public void merge_agentState_lastWins() {
    EventActions first =
        EventActions.builder().agentState(ImmutableMap.of("current_sub_agent", "a")).build();
    EventActions second =
        EventActions.builder().agentState(ImmutableMap.of("current_sub_agent", "b")).build();

    EventActions merged = first.toBuilder().merge(second).build();

    assertThat(merged.agentState()).hasValue(ImmutableMap.of("current_sub_agent", "b"));
  }

  @Test
  public void merge_agentState_disjointKeys_replacesWholeMap() {
    // agentState is a single checkpoint payload: merge replaces it wholesale (last-wins) rather
    // than deep-merging keys.
    EventActions first = EventActions.builder().agentState(ImmutableMap.of("a", 1)).build();
    EventActions second = EventActions.builder().agentState(ImmutableMap.of("b", 2)).build();

    EventActions merged = first.toBuilder().merge(second).build();

    assertThat(merged.agentState()).hasValue(ImmutableMap.of("b", 2));
  }

  @Test
  public void merge_endOfAgentIsOrderIndependent() {
    // A tool that ends the invocation, and one that leaves the flag at its default false. Folding
    // parallel tool responses must keep endOfAgent set whichever order they are merged in.
    EventActions requestsStop = EventActions.builder().endOfAgent(true).build();
    EventActions leavesUnset = EventActions.builder().build();

    EventActions stopFirst = EventActions.builder().merge(requestsStop).merge(leavesUnset).build();
    EventActions stopLast = EventActions.builder().merge(leavesUnset).merge(requestsStop).build();

    assertThat(stopFirst.endOfAgent()).isTrue();
    assertThat(stopLast.endOfAgent()).isTrue();
  }

  @Test
  public void setArtifactDelta_copiesRegularMap() {
    EventActions eventActions = new EventActions();
    ImmutableMap<String, Integer> artifactDelta = ImmutableMap.of("artifact1", 1);

    eventActions.setArtifactDelta(artifactDelta);

    assertThat(eventActions.artifactDelta()).containsExactly("artifact1", 1);
  }

  @Test
  public void removeStateByKey_marksKeyAsRemoved() {
    EventActions eventActions = new EventActions();
    eventActions.stateDelta().put("key1", "value1");
    eventActions.removeStateByKey("key1");

    assertThat(eventActions.stateDelta()).containsExactly("key1", State.REMOVED);
  }

  @Test
  public void builderStateDelta_withNullMap_initializesEmptyMap() {
    EventActions eventActions = EventActions.builder().stateDelta(null).build();

    assertThat(eventActions.stateDelta()).isEmpty();
  }

  @Test
  public void builderStateDelta_withNullValue_marksKeyAsRemoved() {
    Map<String, Object> inputDelta = new HashMap<>();
    inputDelta.put("key1", "value1");
    inputDelta.put("key2", null);

    EventActions eventActions = EventActions.builder().stateDelta(inputDelta).build();

    assertThat(eventActions.stateDelta()).containsExactly("key1", "value1", "key2", State.REMOVED);
  }

  @Test
  public void jsonDeserialization_withNullValueInStateDelta_deserializesAsRemoved()
      throws Exception {
    String json = "{\"stateDelta\":{\"key1\":\"value1\",\"key2\":null}}";
    EventActions deserialized = EventActions.fromJsonString(json, EventActions.class);

    assertThat(deserialized.stateDelta()).containsExactly("key1", "value1", "key2", State.REMOVED);
  }

  @Test
  public void jsonSerialization_works() throws Exception {
    EventActions eventActions =
        EventActions.builder()
            .deletedArtifactIds(ImmutableSet.of("d1", "d2"))
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("k", "v")))
            .build();

    String json = eventActions.toJson();
    EventActions deserialized = EventActions.fromJsonString(json, EventActions.class);

    assertThat(deserialized).isEqualTo(eventActions);
    assertThat(deserialized.deletedArtifactIds()).containsExactly("d1", "d2");
  }

  @Test
  @SuppressWarnings("unchecked") // the nested map is known to be Map<String, Object>
  public void merge_deeplyMergesStateDelta() {
    EventActions eventActions1 = EventActions.builder().build();
    eventActions1.stateDelta().put("a", 1);
    eventActions1.stateDelta().put("b", ImmutableMap.of("nested1", 10, "nested2", 20));
    eventActions1.stateDelta().put("c", 100);
    EventActions eventActions2 = EventActions.builder().build();
    eventActions2.stateDelta().put("a", 2);
    eventActions2.stateDelta().put("b", ImmutableMap.of("nested2", 22, "nested3", 30));
    eventActions2.stateDelta().put("d", 200);

    EventActions merged = eventActions1.toBuilder().merge(eventActions2).build();

    assertThat(merged.stateDelta().keySet()).containsExactly("a", "b", "c", "d");
    assertThat(merged.stateDelta()).containsEntry("a", 2);
    assertThat((Map<String, Object>) merged.stateDelta().get("b"))
        .containsExactly("nested1", 10, "nested2", 22, "nested3", 30);
    assertThat(merged.stateDelta()).containsEntry("c", 100);
    assertThat(merged.stateDelta()).containsEntry("d", 200);
  }

  @Test
  public void merge_failsOnMismatchedKeyTypesNestedInStateDelta() {
    EventActions eventActions1 = EventActions.builder().build();
    eventActions1.stateDelta().put("nested", ImmutableMap.of("a", 1));
    EventActions eventActions2 = EventActions.builder().build();
    eventActions2.stateDelta().put("nested", ImmutableMap.of(1, 2));

    assertThrows(
        IllegalArgumentException.class, () -> eventActions1.toBuilder().merge(eventActions2));
  }

  @Test
  public void setRequestedToolConfirmations_withRegularMap_createsConcurrentMap() {
    ImmutableMap<String, ToolConfirmation> map = ImmutableMap.of("tool", TOOL_CONFIRMATION);

    EventActions actions = new EventActions();
    actions.setRequestedToolConfirmations(map);

    assertThat(actions.requestedToolConfirmations()).isNotSameInstanceAs(map);
    assertThat(actions.requestedToolConfirmations()).isInstanceOf(ConcurrentMap.class);
    assertThat(actions.requestedToolConfirmations()).containsExactly("tool", TOOL_CONFIRMATION);
  }
}
