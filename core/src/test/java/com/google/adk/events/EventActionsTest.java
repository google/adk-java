package com.google.adk.events;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.tools.ToolConfirmation;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Part;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventActionsTest {

  private static final Part PART = Part.builder().text("text").build();
  private static final ToolConfirmation TOOL_CONFIRMATION =
      ToolConfirmation.builder().hint("hint").confirmed(true).build();

  @Test
  public void builder_createsEventActions() {
    var stateDelta = new ConcurrentHashMap<String, Object>();
    stateDelta.put("key", "value");
    var artifactDelta = new ConcurrentHashMap<String, Part>();
    artifactDelta.put("artifact", PART);
    var requestedAuthConfigs = new ConcurrentHashMap<String, ConcurrentMap<String, Object>>();
    requestedAuthConfigs.put("config", new ConcurrentHashMap<>());
    var requestedToolConfirmations = new ConcurrentHashMap<String, ToolConfirmation>();
    requestedToolConfirmations.put("tool", TOOL_CONFIRMATION);

    EventActions eventActions =
        EventActions.builder()
            .skipSummarization(true)
            .stateDelta(stateDelta)
            .artifactDelta(artifactDelta)
            .transferToAgent("agentId")
            .escalate(true)
            .requestedAuthConfigs(requestedAuthConfigs)
            .requestedToolConfirmations(requestedToolConfirmations)
            .endInvocation(true)
            .build();

    assertThat(eventActions.skipSummarization()).hasValue(true);
    assertThat(eventActions.stateDelta()).isEqualTo(stateDelta);
    assertThat(eventActions.artifactDelta()).isEqualTo(artifactDelta);
    assertThat(eventActions.transferToAgent()).hasValue("agentId");
    assertThat(eventActions.escalate()).hasValue(true);
    assertThat(eventActions.requestedAuthConfigs()).isEqualTo(requestedAuthConfigs);
    assertThat(eventActions.requestedToolConfirmations()).isEqualTo(requestedToolConfirmations);
    assertThat(eventActions.endInvocation()).hasValue(true);
  }

  @Test
  public void equalsAndHashCode_withEqualObjects_areEqual() {
    EventActions eventActions1 = EventActions.builder().skipSummarization(true).build();
    EventActions eventActions2 = EventActions.builder().skipSummarization(true).build();

    assertThat(eventActions1).isEqualTo(eventActions2);
    assertThat(eventActions1.hashCode()).isEqualTo(eventActions2.hashCode());
  }

  @Test
  public void equalsAndHashCode_withDifferentObjects_areNotEqual() {
    EventActions eventActions1 = EventActions.builder().skipSummarization(true).build();
    EventActions eventActions2 = EventActions.builder().skipSummarization(false).build();

    assertThat(eventActions1).isNotEqualTo(eventActions2);
    assertThat(eventActions1.hashCode()).isNotEqualTo(eventActions2.hashCode());
  }

  @Test
  public void toBuilder_createsBuilderWithSameValues() {
    EventActions eventActions = EventActions.builder().skipSummarization(true).build();

    EventActions eventActions2 = eventActions.toBuilder().build();

    assertThat(eventActions).isEqualTo(eventActions2);
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

  @Test
  public void setStateDelta_setsValue() {
    var eventActions = new EventActions();
    var stateDelta = new ConcurrentHashMap<String, Object>();
    stateDelta.put("key", "value");

    eventActions.setStateDelta(stateDelta);

    assertThat(eventActions.stateDelta()).isEqualTo(stateDelta);
  }

  @Test
  public void setArtifactDelta_setsValue() {
    var eventActions = new EventActions();
    var artifactDelta = new ConcurrentHashMap<String, Part>();
    artifactDelta.put("artifact", PART);

    eventActions.setArtifactDelta(artifactDelta);

    assertThat(eventActions.artifactDelta()).isEqualTo(artifactDelta);
  }

  @Test
  public void setRequestedAuthConfigs_setsValue() {
    var eventActions = new EventActions();
    var requestedAuthConfigs = new ConcurrentHashMap<String, ConcurrentMap<String, Object>>();
    requestedAuthConfigs.put("config", new ConcurrentHashMap<>());

    eventActions.setRequestedAuthConfigs(requestedAuthConfigs);

    assertThat(eventActions.requestedAuthConfigs()).isEqualTo(requestedAuthConfigs);
  }

  @Test
  public void setRequestedToolConfirmations_setsValue() {
    var eventActions = new EventActions();
    var requestedToolConfirmations = new ConcurrentHashMap<String, ToolConfirmation>();
    requestedToolConfirmations.put("tool", TOOL_CONFIRMATION);

    eventActions.setRequestedToolConfirmations(requestedToolConfirmations);

    assertThat(eventActions.requestedToolConfirmations()).isEqualTo(requestedToolConfirmations);
  }

  @Test
  public void setSkipSummarization_boolean_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setSkipSummarization(true);

    assertThat(eventActions.skipSummarization()).hasValue(true);
  }

  @Test
  public void setSkipSummarization_optional_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setSkipSummarization(Optional.of(true));

    assertThat(eventActions.skipSummarization()).hasValue(true);
  }

  @Test
  public void setTransferToAgent_string_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setTransferToAgent("agentId");

    assertThat(eventActions.transferToAgent()).hasValue("agentId");
  }

  @Test
  public void setTransferToAgent_optional_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setTransferToAgent(Optional.of("agentId"));

    assertThat(eventActions.transferToAgent()).hasValue("agentId");
  }

  @Test
  public void setEscalate_boolean_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setEscalate(true);

    assertThat(eventActions.escalate()).hasValue(true);
  }

  @Test
  public void setEscalate_optional_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setEscalate(Optional.of(true));

    assertThat(eventActions.escalate()).hasValue(true);
  }

  @Test
  public void setEndInvocation_boolean_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setEndInvocation(true);

    assertThat(eventActions.endInvocation()).hasValue(true);
  }

  @Test
  public void setEndInvocation_optional_setsValue() {
    EventActions eventActions = new EventActions();

    eventActions.setEndInvocation(Optional.of(true));

    assertThat(eventActions.endInvocation()).hasValue(true);
  }
}
