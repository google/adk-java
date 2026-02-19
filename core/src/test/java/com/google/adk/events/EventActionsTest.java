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

import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.HashMap;
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
    Map<String, Integer> artifactDelta = new HashMap<>();
    artifactDelta.put("d1", null);
    EventActions eventActionsWithSkipSummarization =
        EventActions.builder()
            .skipSummarization(true)
            .compaction(COMPACTION)
            .artifactDelta(artifactDelta)
            .build();

    EventActions eventActionsAfterRebuild = eventActionsWithSkipSummarization.toBuilder().build();

    assertThat(eventActionsAfterRebuild).isEqualTo(eventActionsWithSkipSummarization);
    assertThat(eventActionsAfterRebuild.compaction()).hasValue(COMPACTION);
  }

  @Test
  public void merge_mergesAllFields() {
    Map<String, Integer> artifactDelta1 = new HashMap<>();
    artifactDelta1.put("artifact1", 1);
    artifactDelta1.put("deleted1", null);
    EventActions eventActions1 =
        EventActions.builder()
            .skipSummarization(true)
            .stateDelta(new HashMap<>(ImmutableMap.of("key1", "value1")))
            .artifactDelta(artifactDelta1)
            .requestedAuthConfigs(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("config1", new ConcurrentHashMap<>(ImmutableMap.of("k", "v")))))
            .requestedToolConfirmations(
                new ConcurrentHashMap<>(ImmutableMap.of("tool1", TOOL_CONFIRMATION)))
            .compaction(COMPACTION)
            .build();
    Map<String, Integer> artifactDelta2 = new HashMap<>();
    artifactDelta2.put("artifact2", 2);
    artifactDelta2.put("deleted2", null);
    EventActions eventActions2 =
        EventActions.builder()
            .stateDelta(new HashMap<>(ImmutableMap.of("key2", "value2")))
            .artifactDelta(artifactDelta2)
            .transferToAgent("agentId")
            .escalate(true)
            .requestedAuthConfigs(
                new HashMap<>(ImmutableMap.of("config2", new HashMap<>(ImmutableMap.of("k", "v")))))
            .requestedToolConfirmations(new HashMap<>(ImmutableMap.of("tool2", TOOL_CONFIRMATION)))
            .endOfAgent(true)
            .build();

    EventActions merged = eventActions1.toBuilder().merge(eventActions2).build();

    assertThat(merged.skipSummarization()).hasValue(true);
    assertThat(merged.stateDelta()).containsExactly("key1", "value1", "key2", "value2");
    assertThat(merged.artifactDelta())
        .containsExactly("artifact1", 1, "artifact2", 2, "deleted1", null, "deleted2", null);
    assertThat(merged.transferToAgent()).hasValue("agentId");
    assertThat(merged.escalate()).hasValue(true);
    assertThat(merged.requestedAuthConfigs())
        .containsExactly(
            "config1", ImmutableMap.of("k", "v"), "config2", ImmutableMap.of("k", "v"));
    assertThat(merged.requestedToolConfirmations())
        .containsExactly("tool1", TOOL_CONFIRMATION, "tool2", TOOL_CONFIRMATION);
    assertThat(merged.endOfAgent()).isTrue();
    assertThat(merged.compaction()).hasValue(COMPACTION);
  }

  @Test
  public void removeStateByKey_marksKeyAsRemoved() {
    EventActions eventActions = new EventActions();
    eventActions.stateDelta().put("key1", "value1");
    eventActions.stateDelta().put("key1", null);

    assertThat(eventActions.stateDelta()).containsExactly("key1", null);
  }
}
