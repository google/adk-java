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

import com.google.adk.tools.ToolConfirmation;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Part;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventActionsTest {

  private static final Part PART = Part.builder().text("text").build();
  private static final ToolConfirmation TOOL_CONFIRMATION =
      ToolConfirmation.builder().hint("hint").confirmed(true).build();

  @Test
  public void toBuilder_createsBuilderWithSameValues() {
    EventActions eventActionsWithSkipSummarization =
        EventActions.builder().skipSummarization(true).build();

    EventActions eventActionsAfterRebuild = eventActionsWithSkipSummarization.toBuilder().build();

    assertThat(eventActionsAfterRebuild).isEqualTo(eventActionsWithSkipSummarization);
  }

  @Test
  public void merge_mergesAllFields() {
    EventActions eventActions1 =
        EventActions.builder()
            .skipSummarization(true)
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key1", "value1")))
            .artifactDelta(new ConcurrentHashMap<>(ImmutableMap.of("artifact1", PART)))
            .requestedAuthConfigs(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("config1", new ConcurrentHashMap<>(ImmutableMap.of("k", "v")))))
            .requestedToolConfirmations(
                new ConcurrentHashMap<>(ImmutableMap.of("tool1", TOOL_CONFIRMATION)))
            .build();
    EventActions eventActions2 =
        EventActions.builder()
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key2", "value2")))
            .artifactDelta(new ConcurrentHashMap<>(ImmutableMap.of("artifact2", PART)))
            .transferToAgent("agentId")
            .escalate(true)
            .requestedAuthConfigs(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("config2", new ConcurrentHashMap<>(ImmutableMap.of("k", "v")))))
            .requestedToolConfirmations(
                new ConcurrentHashMap<>(ImmutableMap.of("tool2", TOOL_CONFIRMATION)))
            .endInvocation(true)
            .build();

    EventActions merged = eventActions1.toBuilder().merge(eventActions2).build();

    assertThat(merged.skipSummarization()).hasValue(true);
    assertThat(merged.stateDelta()).containsExactly("key1", "value1", "key2", "value2");
    assertThat(merged.artifactDelta()).containsExactly("artifact1", PART, "artifact2", PART);
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
    assertThat(merged.endInvocation()).hasValue(true);
  }
}
