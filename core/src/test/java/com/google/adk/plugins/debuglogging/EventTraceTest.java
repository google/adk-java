/*
 * Copyright 2026 Google LLC
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

package com.google.adk.plugins.debuglogging;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Covers the event envelope, and the two details in its actions block that carry real risk. */
@RunWith(JUnit4.class)
public class EventTraceTest {

  private static final String AUTHOR = "assistant";

  private final ObjectMapper mapper = DebugYamlWriter.configure(new ObjectMapper());

  private String serialize(Event event) throws Exception {
    return mapper.writeValueAsString(EventTrace.from(event));
  }

  private static Event.Builder eventBuilder() {
    return Event.builder().id("event-1").author(AUTHOR);
  }

  @Test
  public void serialize_plainEvent_carriesIdAuthorAndFinality() throws Exception {
    String json = serialize(eventBuilder().build());

    assertThat(json).contains("\"event_id\":\"event-1\"");
    assertThat(json).contains("\"author\":\"assistant\"");
    assertThat(json).contains("\"is_final_response\":");
  }

  @Test
  public void serialize_content_isDelegatedToContentTrace() throws Exception {
    Event event =
        eventBuilder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.builder().text("hi").build()))
                    .build())
            .build();

    assertThat(serialize(event))
        .contains("\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi\"}]}");
  }

  @Test
  public void serialize_noActions_omitsTheBlockEntirely() throws Exception {
    assertThat(serialize(eventBuilder().build())).doesNotContain("actions");
  }

  /** The map is free-form and auth-related; a trace gets pasted into bug reports. */
  @Test
  public void serialize_requestedAuthConfigs_recordsTheCountAndNeverTheContent() throws Exception {
    Map<String, Map<String, Object>> authConfigs = new HashMap<>();
    authConfigs.put("my-oauth", ImmutableMap.of("client_secret", "s3cret"));
    EventActions actions = EventActions.builder().requestedAuthConfigs(authConfigs).build();

    String json = serialize(eventBuilder().actions(actions).build());

    assertThat(json).contains("\"requested_auth_configs\":1");
    assertThat(json).doesNotContain("s3cret");
    assertThat(json).doesNotContain("my-oauth");
  }

  @Test
  public void serialize_artifactDelta_keepsTheFilenameToVersionMapping() throws Exception {
    Map<String, Integer> artifactDelta = new HashMap<>();
    artifactDelta.put("report.pdf", 3);
    EventActions actions = EventActions.builder().artifactDelta(artifactDelta).build();

    assertThat(serialize(eventBuilder().actions(actions).build()))
        .contains("\"artifact_delta\":{\"report.pdf\":3}");
  }

  @Test
  public void serialize_stateDelta_goesThroughSafeSerializer() throws Exception {
    Map<String, Object> stateDelta = new HashMap<>();
    stateDelta.put("counter", 4);
    EventActions actions = EventActions.builder().stateDelta(stateDelta).build();

    assertThat(serialize(eventBuilder().actions(actions).build()))
        .contains("\"state_delta\":{\"counter\":4}");
  }

  @Test
  public void serialize_transferToAgent_isRecorded() throws Exception {
    EventActions actions = EventActions.builder().transferToAgent("billing_agent").build();

    assertThat(serialize(eventBuilder().actions(actions).build()))
        .contains("\"transfer_to_agent\":\"billing_agent\"");
  }

  /** The trace is a snapshot: mutating the source afterwards must not change it. */
  @Test
  public void from_copiesTheLiveActionMapsRatherThanReferencingThem() {
    Map<String, Integer> artifactDelta = new HashMap<>();
    artifactDelta.put("first.txt", 1);
    EventActions actions = EventActions.builder().artifactDelta(artifactDelta).build();

    EventTrace trace = EventTrace.from(eventBuilder().actions(actions).build());
    actions.artifactDelta().put("sneaked-in.txt", 9);

    assertThat(trace.actions().orElseThrow().artifactDelta()).containsExactly("first.txt", 1);
  }

  @Test
  public void serialize_emptyLongRunningToolIds_isDroppedNotEmittedAsAnEmptyList()
      throws Exception {
    assertThat(serialize(eventBuilder().build())).doesNotContain("long_running_tool_ids");
  }

  @Test
  public void serialize_longRunningToolIds_whenPresent_areRecorded() throws Exception {
    Event event = eventBuilder().longRunningToolIds(ImmutableSet.of("call-7")).build();

    assertThat(serialize(event)).contains("\"long_running_tool_ids\":[\"call-7\"]");
  }

  /** The streaming flags: both are tri-state, so the absent case must not read as {@code false}. */
  @Test
  public void serialize_streamingFlags_areRecorded() throws Exception {
    Event event = eventBuilder().partial(true).turnComplete(false).build();

    String json = serialize(event);

    assertThat(json).contains("\"partial\":true");
    assertThat(json).contains("\"turn_complete\":false");
  }

  @Test
  public void serialize_absentStreamingFlags_omitTheKeysRatherThanWritingFalse() throws Exception {
    String json = serialize(eventBuilder().build());

    assertThat(json).doesNotContain("partial");
    assertThat(json).doesNotContain("turn_complete");
  }

  /** The event's own branch, not {@link TracePayload.BranchTrace}'s — a separate code path. */
  @Test
  public void serialize_branch_isRecorded() throws Exception {
    Event event = eventBuilder().branch("root.billing_agent").build();

    assertThat(serialize(event)).contains("\"branch\":\"root.billing_agent\"");
  }

  @Test
  public void serialize_errorCodeAndMessage_areRecorded() throws Exception {
    Event event =
        eventBuilder()
            .errorCode(new FinishReason(FinishReason.Known.SAFETY))
            .errorMessage("blocked by a safety filter")
            .build();

    String json = serialize(event);

    assertThat(json).contains("\"error_code\":\"SAFETY\"");
    assertThat(json).contains("\"error_message\":\"blocked by a safety filter\"");
  }

  /** Same reduction the response trace makes: the payload is large and adds nothing to a trace. */
  @Test
  public void serialize_groundingMetadata_isReducedToABoolean() throws Exception {
    Event event = eventBuilder().groundingMetadata(GroundingMetadata.builder().build()).build();

    String json = serialize(event);

    assertThat(json).contains("\"has_grounding_metadata\":true");
    assertThat(json).doesNotContain("groundingChunks");
  }

  /** An event carries the three-count subset; the cached count belongs to the response only. */
  @Test
  public void serialize_usageMetadata_carriesThreeCountsAndOmitsTheCachedOne() throws Exception {
    Event event =
        eventBuilder()
            .usageMetadata(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(120)
                    .candidatesTokenCount(45)
                    .totalTokenCount(165)
                    .cachedContentTokenCount(80)
                    .build())
            .build();

    String json = serialize(event);

    assertThat(json)
        .contains(
            "\"usage_metadata\":{\"prompt_token_count\":120,\"candidates_token_count\":45,"
                + "\"total_token_count\":165}");
    assertThat(json).doesNotContain("cached_content_token_count");
  }

  @Test
  public void serialize_escalate_isRecorded() throws Exception {
    EventActions actions = EventActions.builder().escalate(true).build();

    assertThat(serialize(eventBuilder().actions(actions).build())).contains("\"escalate\":true");
  }
}
