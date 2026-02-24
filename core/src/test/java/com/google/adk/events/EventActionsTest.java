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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
  public void removeStateByKey_marksKeyAsRemoved() {
    EventActions eventActions = new EventActions();
    eventActions.stateDelta().put("key1", "value1");
    eventActions.removeStateByKey("key1");

    assertThat(eventActions.stateDelta()).containsExactly("key1", State.REMOVED);
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
}
