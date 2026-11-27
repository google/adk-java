/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.models.CacheMetadata;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Route;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure JSON/scope tests. These do not claim to verify a JDBC engine or its physical schema. */
final class SessionDataCodecTest {
  private static final String SENTINEL_TEXT = "__ADK_SENTINEL_REMOVED__";
  private final SessionDataCodec codec = new SessionDataCodec();

  @Test
  void nullAndEmptyCombinedStateHaveEmptyScopes() {
    assertThat(codec.splitState(null)).isEqualTo(codec.splitState(Map.of()));
    assertThat(codec.splitState(null).app()).isEmpty();
    assertThat(codec.splitState(null).user()).isEmpty();
    assertThat(codec.splitState(null).local()).isEmpty();
  }

  @Test
  void splitUsesOnlyTheFirstPrefixAndKeepsDottedAndEmptyKeysLiteral() {
    SessionDataCodec.ScopedState split =
        codec.splitState(
            Map.of(
                "app:theme", "dark",
                "user:name", "Ada",
                "settings.name", "local",
                "app:temp:key", "app value",
                "user:app:key", "user value",
                "app:", "empty app key",
                "", "empty local key",
                "temp:hidden", "ignored"));

    assertThat(split.app())
        .containsExactly("theme", "dark", "temp:key", "app value", "", "empty app key");
    assertThat(split.user()).containsExactly("name", "Ada", "app:key", "user value");
    assertThat(split.local()).containsExactly("settings.name", "local", "", "empty local key");
  }

  @Test
  void splitSkipsTempValuesBeforeSerialization() {
    BrokenValue value = new BrokenValue();

    SessionDataCodec.ScopedState split = codec.splitState(Map.of("temp:runtime", value, "ok", 1));

    assertThat(split.local()).containsExactly("ok", 1);
    assertThat(value.reads).isEqualTo(0);
  }

  @Test
  void splitPreservesDeletionIdentityWithoutInterpretingLiteralSentinelStrings() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("app:deleted", State.REMOVED);
    state.put("user:deleted", null);
    state.put("deleted", State.REMOVED);
    state.put("literal", SENTINEL_TEXT);
    state.put("nested", nullableValue());

    SessionDataCodec.ScopedState split = codec.splitState(state);

    assertThat(split.app().get("deleted")).isSameInstanceAs(State.REMOVED);
    assertThat(split.user().get("deleted")).isSameInstanceAs(State.REMOVED);
    assertThat(split.local().get("deleted")).isSameInstanceAs(State.REMOVED);
    assertThat(split.local().get("literal")).isEqualTo(SENTINEL_TEXT);
    assertThat(split.local().get("nested")).isEqualTo(nullableValue());
    assertThat(state).containsEntry("user:deleted", null);
  }

  @Test
  void splitSnapshotsAreDetachedAndTheirTopLevelMapsAreUnmodifiable() {
    List<String> items = new ArrayList<>(List.of("original"));
    Map<String, Object> state = new LinkedHashMap<>(Map.of("app:items", items));

    SessionDataCodec.ScopedState split = codec.splitState(state);
    items.add("later");
    state.put("other", 1);

    assertThat(split.app()).containsExactly("items", List.of("original"));
    assertThat(split.local()).isEmpty();
    assertThrows(UnsupportedOperationException.class, () -> split.app().put("changed", true));
    assertThrows(UnsupportedOperationException.class, () -> split.user().put("changed", true));
    assertThrows(UnsupportedOperationException.class, () -> split.local().put("changed", true));
  }

  @Test
  void stateDocumentsOmitDeletedEntriesButKeepNestedNullsAndLiteralStrings() throws Exception {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("removed", State.REMOVED);
    document.put("null", null);
    document.put("literal", SENTINEL_TEXT);
    document.put("nested", nullableValue());
    document.put("empty", Map.of());

    String json = codec.writeState(document);
    Map<String, Object> restored = codec.readState(json);

    assertThat(restored)
        .containsExactly("literal", SENTINEL_TEXT, "nested", nullableValue(), "empty", Map.of());
    assertThat(tree(json).has("removed")).isFalse();
    assertThat(tree(json).has("null")).isFalse();
    assertThat(document.get("removed")).isSameInstanceAs(State.REMOVED);
    assertThat(document).containsEntry("null", null);
  }

  @Test
  void readStateTreatsOnlyTopLevelNullAsAbsent() {
    assertThat(codec.readState("{\"gone\":null,\"nested\":{\"value\":null}}"))
        .containsExactly("nested", nullableValue());
  }

  @Test
  void alreadyScopedDocumentsKeepPrefixLookingAndDottedKeys() {
    Map<String, Object> document = Map.of("temp:key", 1, "app:key", 2, "user:key", 3, "a.b", 4);

    assertThat(codec.readState(codec.writeState(document))).isEqualTo(document);
  }

  @Test
  void eventSerializationOverridesDoNotChangeArbitraryModelValuesInsideState() throws Exception {
    EventActions value =
        EventActions.builder()
            .stateDelta(Map.of("temp:literalPayload", 1, "removed", State.REMOVED))
            .build();
    Map<String, Object> state = Map.of("modelAsUserData", value);

    assertThat(tree(codec.writeState(state))).isEqualTo(tree(JsonBaseModel.toJsonString(state)));
    assertThat(codec.splitState(state).local().get("modelAsUserData"))
        .isEqualTo(JsonBaseModel.getMapper().readValue(value.toJson(), Map.class));
  }

  @Test
  void onlyRootEventActionsDeltaUsesPersistenceControlRules() throws Exception {
    EventActions nested =
        EventActions.builder()
            .stateDelta(Map.of("temp:literal", 1, "literalRemoval", State.REMOVED))
            .build();
    Event original =
        event(Map.of("temp:root", 1, "removed", State.REMOVED)).toBuilder()
            .output(Map.of("model", nested))
            .build();
    String json = codec.writeEvent(original);
    assertThat(tree(json).path("output").path("model")).isEqualTo(tree(nested.toJson()));
    assertThat(tree(json).path("actions").path("stateDelta").has("temp:root")).isFalse();
    assertThat(codec.readEvent(json).actions().stateDelta().get("removed"))
        .isSameInstanceAs(State.REMOVED);
  }

  @Test
  void stateNumbersFollowTheAdkMapperWithoutLosingLargeIntegers() throws Exception {
    Map<String, Object> state =
        Map.of(
            "beyondDoubleInteger",
            9_007_199_254_740_993L,
            "beyondLong",
            new BigInteger("123456789012345678901234567890"),
            "fraction",
            1.25,
            "false",
            false,
            "emptyString",
            "");

    Map<String, Object> restored = codec.readState(codec.writeState(state));

    assertThat(restored)
        .isEqualTo(
            JsonBaseModel.getMapper().readValue(JsonBaseModel.toJsonString(state), Map.class));
    assertThat(restored.get("beyondDoubleInteger")).isEqualTo(9_007_199_254_740_993L);
    assertThat(restored.get("beyondLong"))
        .isEqualTo(new BigInteger("123456789012345678901234567890"));
  }

  @Test
  void snapshotsUseActualJsonRepresentationsAndDetachBinaryInput() {
    byte[] bytes = {1, 2, 3};

    SessionDataCodec.ScopedState snapshot = codec.splitState(Map.of("data", bytes));
    bytes[0] = 9;

    assertThat(snapshot.local()).containsExactly("data", "AQID");
  }

  @Test
  void applyStateDeletesReplacesWholeMapsAndDoesNotDeepMergeDottedKeys() {
    Map<String, Object> current =
        Map.of("map", Map.of("old", 1), "gone", 2, "alsoGone", 3, "a.b", 4, "a", Map.of("b", 5));
    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put("map", Map.of("new", 6));
    delta.put("gone", State.REMOVED);
    delta.put("alsoGone", null);
    delta.put("a.b", 7);
    delta.put("literal", SENTINEL_TEXT);

    Map<String, Object> updated = codec.applyState(current, delta);

    assertThat(updated)
        .containsExactly(
            "map", Map.of("new", 6), "a.b", 7, "a", Map.of("b", 5), "literal", SENTINEL_TEXT);
    assertThat(current).containsEntry("map", Map.of("old", 1));
    assertThat(current).containsKey("gone");
    assertThat(delta.get("gone")).isSameInstanceAs(State.REMOVED);
    assertThat(delta).containsEntry("alsoGone", null);
  }

  @Test
  void applyStateWithoutDeltaStillReturnsOnlyActiveValuesInANewMap() {
    Map<String, Object> current =
        new LinkedHashMap<>(Map.of("items", List.of("original"), "gone", State.REMOVED));
    current.put("null", null);

    Map<String, Object> updated = codec.applyState(current, null);
    updated.put("later", true);

    assertThat(updated).containsExactly("items", List.of("original"), "later", true);
    assertThat(current).containsKey("gone");
    assertThat(current).doesNotContainKey("later");
  }

  @Test
  void applyingADeltaToADecodedDocumentWritesTheSameTextAsEncodingTheResultTwice()
      throws Exception {
    Map<String, Object> current =
        codec.readState(
            "{\"kept\":{\"n\":null,\"big\":123456789012345678901234567890},\"f\":-0.0,\"gone\":1}");
    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put("gone", State.REMOVED);
    delta.put("exponent", 1e23);
    delta.put("bytes", new byte[] {1, 2, 3});
    delta.put("text", "before\0after");

    String written = codec.writeState(codec.applyState(current, codec.splitState(delta).local()));

    assertThat(written).isEqualTo(codec.writeState(codec.readState(written)));
    assertThat(tree(written))
        .isEqualTo(
            tree(
                "{\"kept\":{\"n\":null,\"big\":123456789012345678901234567890},\"f\":-0.0,"
                    + "\"exponent\":1.0E23,\"bytes\":\"AQID\",\"text\":\"before\\u0000after\"}"));
  }

  @Test
  void mergeKeepsScopesDistinctAndDoesNotResurrectDeletedSharedValuesFromLocalState() {
    Map<String, Object> local =
        Map.of(
            "key",
            "local",
            "app:key",
            "stale",
            "user:deleted",
            "stale",
            "temp:runtime",
            new BrokenValue());
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("key", "user");
    user.put("deleted", State.REMOVED);
    user.put("null", null);

    Map<String, Object> merged = codec.mergeState(Map.of("key", "app"), user, local);

    assertThat(merged).containsExactly("key", "local", "app:key", "app", "user:key", "user");
    assertThat(codec.mergeState(Map.of(), Map.of(), Map.of("app:deleted", "stale"))).isEmpty();
    assertThat(local).containsEntry("app:key", "stale");
    assertThat(user.get("deleted")).isSameInstanceAs(State.REMOVED);
  }

  @Test
  void mergingScopedSuffixesDoesNotDropLegitimatePrefixLookingRawKeys() {
    SessionDataCodec.ScopedState split =
        codec.splitState(Map.of("app:temp:key", 1, "user:app:key", 2, "app:", 3));

    assertThat(codec.mergeState(split.app(), split.user(), split.local()))
        .containsExactly("app:temp:key", 1, "user:app:key", 2, "app:", 3);
  }

  @Test
  void mergingSeparatelyDecodedDocumentsReturnsIndependentlyMutableSnapshots() {
    String app = "{\"items\":[\"original\"]}";
    Map<String, Object> first = codec.mergeState(codec.readState(app), Map.of(), Map.of());
    Map<String, Object> second = codec.mergeState(codec.readState(app), Map.of(), Map.of());

    first.put("local", true);
    ((List<?>) first.get("app:items")).clear();

    assertThat(second).containsExactly("app:items", List.of("original"));
  }

  @Test
  void minimalEventRoundTripsWithZeroTimestampAndEmptyActions() throws Exception {
    Event original = event(Map.of());

    Event restored = codec.readEvent(codec.writeEvent(original));

    assertThat(restored.id()).isEqualTo("event-雪");
    assertThat(restored.timestamp()).isEqualTo(0);
    assertThat(tree(restored.toJson())).isEqualTo(tree(original.toJson()));
    assertThat(restored.actions().stateDelta()).isEmpty();
  }

  @Test
  void fullEventRetainsEveryModeledFieldIncludingBinaryAndWorkflowData() throws Exception {
    Event original = fullEvent();
    String json = codec.writeEvent(original);

    Event restored = codec.readEvent(json);

    assertThat(tree(json)).isEqualTo(tree(original.toJson()));
    assertThat(tree(restored.toJson())).isEqualTo(tree(original.toJson()));
    assertThat(tree(json).has("formatVersion")).isFalse();
    assertThat(tree(json).has("event")).isFalse();
    assertThat(
            restored
                .content()
                .orElseThrow()
                .parts()
                .orElseThrow()
                .get(0)
                .thoughtSignature()
                .orElseThrow())
        .isEqualTo(new byte[] {1, 2, 3});
    assertThat(
            restored
                .content()
                .orElseThrow()
                .parts()
                .orElseThrow()
                .get(3)
                .inlineData()
                .orElseThrow()
                .data()
                .orElseThrow())
        .isEqualTo(new byte[] {4, 5, 6});
    assertThat(restored.actions().compaction()).isPresent();
    assertThat(restored.nodeInfo()).isPresent();
    assertThat(restored.cacheMetadata()).isPresent();
    assertThat(restored.cacheMetadata()).isEqualTo(original.cacheMetadata());
    assertThat(restored.actions().route())
        .hasValue(
            List.of(
                new Route.Tag("next"),
                new Route.Num(2),
                new Route.Flag(false),
                Route.Default.INSTANCE));
  }

  @Test
  void structuredToolArgumentsAndConfirmationPayloadKeepTheirJsonShape() throws Exception {
    ToolConfirmation confirmation =
        ToolConfirmation.builder()
            .hint("Approve?")
            .confirmed(true)
            .payload(nullableValue())
            .build();
    Event original =
        event(Map.of()).toBuilder()
            .actions(
                EventActions.builder()
                    .requestedToolConfirmations(Map.of("call", confirmation))
                    .build())
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("confirmation")
                                .name("adk_request_confirmation")
                                .args(
                                    Map.of(
                                        "originalFunctionCall",
                                        FunctionCall.builder().id("call").name("tool").build(),
                                        "toolConfirmation",
                                        confirmation))
                                .build())
                        .build()))
            .build();

    Event restored = codec.readEvent(codec.writeEvent(original));

    assertThat(tree(restored.toJson())).isEqualTo(tree(original.toJson()));
    assertThat(restored.actions().requestedToolConfirmations().get("call").payload())
        .isEqualTo(nullableValue());
  }

  @Test
  void eventDeltaStoresRealRemovalsAsNullAndKeepsLiteralSentinelString() throws Exception {
    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put("app:deleted", State.REMOVED);
    delta.put("user:deleted", null);
    delta.put("literal", SENTINEL_TEXT);
    delta.put("nested", nullableValue());
    Event original = event(delta);

    String json = codec.writeEvent(original);
    Event restored = codec.readEvent(json);

    assertThat(tree(json).path("actions").path("stateDelta").path("app:deleted").isNull()).isTrue();
    assertThat(restored.actions().stateDelta().get("app:deleted")).isSameInstanceAs(State.REMOVED);
    assertThat(restored.actions().stateDelta().get("user:deleted")).isSameInstanceAs(State.REMOVED);
    assertThat(restored.actions().stateDelta().get("literal")).isEqualTo(SENTINEL_TEXT);
    assertThat(restored.actions().stateDelta().get("nested")).isEqualTo(nullableValue());
    assertThat(codec.writeEvent(restored)).isEqualTo(json);
    assertThat(original.actions().stateDelta().get("app:deleted")).isSameInstanceAs(State.REMOVED);
  }

  @Test
  void eventTempValuesAreNeverVisitedOrRemovedFromTheCallerEvent() {
    BrokenValue value = new BrokenValue();
    Event original = event(Map.of("temp:runtime", value, "persisted", 1));

    Event restored = codec.readEvent(codec.writeEvent(original));

    assertThat(restored.actions().stateDelta()).containsExactly("persisted", 1);
    assertThat(original.actions().stateDelta().get("temp:runtime")).isSameInstanceAs(value);
    assertThat(value.reads).isEqualTo(0);
  }

  @Test
  void tempPrefixIsNotRecursivelyStrippedFromUserPayloadOrOtherEventFields() throws Exception {
    Event original =
        event(Map.of("nested", Map.of("temp:key", 1), "app:temp:key", 2, "user:temp:key", 3))
            .toBuilder()
            .actions(
                EventActions.builder()
                    .stateDelta(
                        Map.of(
                            "nested", Map.of("temp:key", 1), "app:temp:key", 2, "user:temp:key", 3))
                    .agentState(Map.of("temp:key", 4))
                    .build())
            .output(Map.of("temp:key", 5))
            .build();

    Event restored = codec.readEvent(codec.writeEvent(original));

    assertThat(tree(restored.toJson())).isEqualTo(tree(original.toJson()));
  }

  @Test
  void readingEventAlsoDropsTempDeltaWithoutChangingOtherPayloads() {
    Event restored =
        codec.readEvent(
            "{\"id\":\"e\",\"timestamp\":1,\"actions\":{\"stateDelta\":{\"temp:discard\":1,\"nested\":{\"temp:keep\":2}}}}");

    assertThat(restored.actions().stateDelta()).containsExactly("nested", Map.of("temp:keep", 2));
  }

  @Test
  void identityLookingFieldsInsideThePayloadDoNotDecideTheRootIdentity() {
    Event restored =
        codec.readEvent(
            "{\"actions\":{\"stateDelta\":{\"id\":5,\"timestamp\":\"later\"}},\"id\":\"e\",\"timestamp\":1}");

    assertThat(restored.id()).isEqualTo("e");
    assertThat(restored.timestamp()).isEqualTo(1);
    assertThat(restored.actions().stateDelta()).containsExactly("id", 5, "timestamp", "later");
  }

  @Test
  void emptyAndNullOptionalEventValuesFollowNativeJavaDefaults() throws Exception {
    for (String json :
        List.of(
            "{\"id\":\"\",\"timestamp\":-1,\"actions\":null,\"content\":null}",
            "{\"id\":\"e\",\"timestamp\":9223372036854775807,\"actions\":{\"stateDelta\":null,\"route\":[]}}")) {
      Event nativeEvent = Event.fromJson(json);

      assertThat(tree(codec.readEvent(json).toJson())).isEqualTo(tree(nativeEvent.toJson()));
      assertThat(tree(codec.readEvent(codec.writeEvent(nativeEvent)).toJson()))
          .isEqualTo(tree(nativeEvent.toJson()));
    }
  }

  @Test
  void codecDoesNotModifyTheSharedAdkMapper() throws Exception {
    ObjectMapper sharedMapper = JsonBaseModel.getMapper();
    Event original = event(Map.of("temp:keepInMemory", 1, "removed", State.REMOVED));
    String before = original.toJson();

    codec.writeEvent(original);
    new SessionDataCodec().writeEvent(original);

    assertThat(JsonBaseModel.getMapper()).isSameInstanceAs(sharedMapper);
    assertThat(original.toJson()).isEqualTo(before);
    assertThat(tree(before).path("actions").path("stateDelta").path("removed").asText())
        .isEqualTo(SENTINEL_TEXT);
    assertThat(tree(before).path("actions").path("stateDelta").has("temp:keepInMemory")).isTrue();
  }

  @Test
  void corruptStateDocumentsFailWithSafeErrors() {
    for (String json :
        Arrays.asList(
            null,
            "",
            "null",
            "[]",
            "true",
            "123",
            "\"private-value\"",
            "{\"private-value\":",
            "{} {}",
            "{} private-value")) {
      assertSafeError(
          assertThrows(SessionException.class, () -> codec.readState(json)),
          "Unable to decode session state.");
    }
  }

  @Test
  void corruptEventDocumentsFailInsteadOfSupplyingMissingIdentityOrWallClock() {
    for (String json :
        Arrays.asList(
            null,
            "",
            "null",
            "[]",
            "123",
            "\"private-value\"",
            "{}",
            "{\"id\":\"private-value\"}",
            "{\"timestamp\":1}",
            "{\"id\":null,\"timestamp\":1}",
            "{\"id\":2,\"timestamp\":1}",
            "{\"id\":{\"id\":\"e\"},\"timestamp\":1}",
            "{\"id\":\"e\",\"timestamp\":[1]}",
            "{\"id\":\"e\",\"timestamp\":null}",
            "{\"id\":\"e\",\"timestamp\":\"1\"}",
            "{\"id\":\"e\",\"timestamp\":1.5}",
            "{\"id\":\"e\",\"timestamp\":9223372036854775808}",
            "{\"id\":\"e\",\"timestamp\":1,\"actions\":{\"stateDelta\":[]}}",
            "{\"id\":\"e\",\"timestamp\":1,\"content\":\"private-value\"}",
            "{\"id\":\"e\",\"timestamp\":1} {}",
            "{\"private-value\":",
            "{\"formatVersion\":1,\"event\":{\"id\":\"e\",\"timestamp\":1}}")) {
      assertSafeError(
          assertThrows(SessionException.class, () -> codec.readEvent(json)),
          "Unable to decode session event.");
    }
  }

  @Test
  void eventWithoutAnIdCannotBeWrittenAsAnUnreadableRecord() {
    assertSafeError(
        assertThrows(
            SessionException.class, () -> codec.writeEvent(Event.builder().timestamp(1).build())),
        "Unable to encode session event.");
  }

  @Test
  void serializationFailuresDoNotLeakPrivateDataCausesOrPartialMutations() {
    BrokenValue value = new BrokenValue();
    Map<String, Object> invalid = Map.of("private-key", value);
    Event original = event(Map.of("temp:keep", 1, "private-key", value, "removed", State.REMOVED));

    assertSafeError(
        assertThrows(SessionException.class, () -> codec.writeState(invalid)),
        "Unable to encode session state.");
    assertSafeError(
        assertThrows(SessionException.class, () -> codec.splitState(invalid)),
        "Unable to split session state.");
    // A value that cannot be encoded never reaches applyState or mergeState: their inputs are
    // snapshots that splitState and readState have already put through JSON.
    assertSafeError(
        assertThrows(SessionException.class, () -> codec.writeEvent(original)),
        "Unable to encode session event.");
    assertThat(original.actions().stateDelta()).containsEntry("temp:keep", 1);
    assertThat(original.actions().stateDelta().get("removed")).isSameInstanceAs(State.REMOVED);
    assertThat(invalid.get("private-key")).isSameInstanceAs(value);
  }

  @Test
  void cyclicStateFailsSafelyInsteadOfLeakingTheSerializerCause() {
    Map<String, Object> cyclic = new LinkedHashMap<>();
    cyclic.put("private-key", cyclic);

    assertSafeError(
        assertThrows(SessionException.class, () -> codec.writeState(cyclic)),
        "Unable to encode session state.");
  }

  private static Event event(Map<String, Object> delta) {
    return Event.builder()
        .id("event-雪")
        .invocationId("invocation")
        .author("agent")
        .timestamp(0)
        .actions(EventActions.builder().stateDelta(delta).build())
        .build();
  }

  private static Map<String, Object> nullableValue() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("value", null);
    return nested;
  }

  private static JsonNode tree(String json) throws Exception {
    return JsonBaseModel.getMapper().readTree(json);
  }

  private static void assertSafeError(SessionException error, String message) {
    assertThat(error).hasMessageThat().isEqualTo(message);
    assertThat(error).hasCauseThat().isNull();
    assertThat(error.getSuppressed()).isEmpty();
  }

  static Event fullEvent() {
    return event(Map.of("nested", nullableValue())).toBuilder()
        .branch("root.child")
        .isolationScope("scope")
        .partial(false)
        .turnComplete(true)
        .errorCode(new FinishReason("error-code"))
        .errorMessage("message")
        .finishReason(new FinishReason("STOP"))
        .usageMetadata(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(20)
                .totalTokenCount(30)
                .build())
        .avgLogprobs(-0.25)
        .interrupted(false)
        .longRunningToolIds(Set.of("call"))
        .modelVersion("model-version")
        .groundingMetadata(GroundingMetadata.builder().webSearchQueries(List.of("query")).build())
        .customMetadata(
            List.of(CustomMetadata.builder().key("source").stringValue("value").build()))
        .inputTranscription(Transcription.builder().text("input").finished(true).build())
        .outputTranscription(Transcription.builder().text("output").finished(false).build())
        .output(Map.of("answer", 42))
        .nodeInfo(
            NodeInfo.builder()
                .path("workflow@1/node@2")
                .outputFor(List.of("workflow@1"))
                .messageAsOutput(true)
                .build())
        .cacheMetadata(
            CacheMetadata.builder()
                .fingerprint("fingerprint")
                .contentsCount(2)
                .cacheName("cache/name")
                .expireTime(Instant.ofEpochSecond(2_000_000_000L, 123_456_789))
                .invocationsUsed(3)
                .createdAt(Instant.ofEpochSecond(1_999_998_200L, 987_654_321))
                .build())
        .actions(
            EventActions.builder()
                .stateDelta(Map.of("nested", nullableValue()))
                .skipSummarization(false)
                .artifactDelta(Map.of("artifact", 2))
                .deletedArtifactIds(Set.of("deleted-artifact"))
                .transferToAgent("next-agent")
                .escalate(false)
                .endOfAgent(true)
                .requestedAuthConfigs(Map.of("call", Map.of("scheme", "oauth")))
                .requestedToolConfirmations(
                    Map.of(
                        "call",
                        ToolConfirmation.builder()
                            .hint("Approve?")
                            .confirmed(false)
                            .payload(Map.of("value", 1))
                            .build()))
                .agentState(Map.of("step", 2))
                .setModelResponse(Map.of("ok", true))
                .rewindBeforeInvocationId("previous-invocation")
                .route(
                    List.of(
                        new Route.Tag("next"),
                        new Route.Num(2),
                        new Route.Flag(false),
                        Route.Default.INSTANCE))
                .compaction(
                    EventCompaction.builder()
                        .startTimestamp(1)
                        .endTimestamp(999)
                        .compactedContent(Content.fromParts(Part.fromText("summary")))
                        .build())
                .build())
        .content(
            Content.fromParts(
                Part.builder()
                    .text("thought")
                    .thought(true)
                    .thoughtSignature(new byte[] {1, 2, 3})
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("call")
                            .name("tool")
                            .args(
                                Map.of(
                                    "input",
                                    "value",
                                    "beyondDoubleInteger",
                                    9_007_199_254_740_993L,
                                    "beyondLong",
                                    new BigInteger("123456789012345678901234567890")))
                            .build())
                    .build(),
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .id("call")
                            .name("tool")
                            .response(Map.of("result", "value"))
                            .build())
                    .build(),
                Part.builder()
                    .inlineData(
                        Blob.builder().data(new byte[] {4, 5, 6}).mimeType("image/png").build())
                    .build()))
        .build();
  }

  private static final class BrokenValue {
    private int reads;

    public String getValue() {
      reads++;
      throw new IllegalStateException("private-value must not escape");
    }
  }
}
