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

package com.google.adk.summarizer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class TailRetentionEventCompactorTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock private BaseSessionService mockSessionService;
  @Mock private BaseEventSummarizer mockSummarizer;
  @Captor private ArgumentCaptor<List<Event>> eventListCaptor;

  @Test
  public void constructor_negativeTokenThreshold_throwsException() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> new TailRetentionEventCompactor(mockSummarizer, 2, -1)))
        .hasMessageThat()
        .contains("tokenThreshold must be non-negative");
  }

  @Test
  public void constructor_negativeRetentionSize_throwsException() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> new TailRetentionEventCompactor(mockSummarizer, -1, 100)))
        .hasMessageThat()
        .contains("retentionSize must be non-negative");
  }

  @Test
  public void compaction_skippedWhenEstimatedTokenUsageBelowThreshold() {
    // Threshold is 100.
    // Event1: "Event1" -> length 6.
    // Retain1: "Retain1" -> length 7.
    // Retain2: "Retain2" -> length 7.
    // Total length = 20. Estimated tokens = 20 / 4 = 5.
    // 5 <= 100 -> Skip.
    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 2, 100);
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"),
            createEvent(2, "Retain1"),
            createEvent(3, "Retain2")); // No usage metadata
    Session session = Session.builder("id").events(events).build();

    compactor.compact(session, mockSessionService).blockingSubscribe();

    verify(mockSummarizer, never()).summarizeEvents(any());
    verify(mockSessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void compaction_happensWhenEstimatedTokenUsageAboveThreshold() {
    // Threshold is 2.
    // Event1: "Event1" -> length 6.
    // Retain1: "Retain1" -> length 7.
    // Retain2: "Retain2" -> length 7.
    // Total eligible for estimation (including retained ones as per current logic):
    // Logic: getCompactionEvents returns [Event1, Retain1, Retain2] for estimation.
    // Total length = 20. Estimated tokens = 20 / 4 = 5.
    // 5 > 2 -> Compact.
    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 2, 2);
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"),
            createEvent(2, "Retain1"),
            createEvent(3, "Retain2")); // No usage metadata
    Session session = Session.builder("id").events(events).build();
    Event summaryEvent = createEvent(4, "Summary");

    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(summaryEvent));
    when(mockSessionService.appendEvent(any(), any())).thenReturn(Single.just(summaryEvent));

    compactor.compact(session, mockSessionService).blockingSubscribe();

    verify(mockSummarizer).summarizeEvents(any());
    verify(mockSessionService).appendEvent(eq(session), eq(summaryEvent));
  }

  @Test
  public void compaction_skippedWhenTokenUsageBelowThreshold() {
    // Threshold is 300, usage is 200.
    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 2, 300);
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"),
            createEvent(2, "Retain1"),
            withUsage(createEvent(3, "Retain2"), 200));
    Session session = Session.builder("id").events(events).build();

    compactor.compact(session, mockSessionService).blockingSubscribe();

    verify(mockSummarizer, never()).summarizeEvents(any());
    verify(mockSessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void compaction_happensWhenTokenUsageAboveThreshold() {
    // Threshold is 300, usage is 400.
    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 2, 300);
    Event event3 = withUsage(createEvent(3, "Retain2"), 400);
    ImmutableList<Event> events =
        ImmutableList.of(createEvent(1, "Event1"), createEvent(2, "Retain1"), event3);
    Session session = Session.builder("id").events(events).build();
    Event summaryEvent = createEvent(4, "Summary");

    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(summaryEvent));
    when(mockSessionService.appendEvent(any(), any())).thenReturn(Single.just(summaryEvent));

    compactor.compact(session, mockSessionService).blockingSubscribe();

    verify(mockSummarizer).summarizeEvents(any());
    verify(mockSessionService).appendEvent(eq(session), eq(summaryEvent));
  }

  @Test
  public void compact_notEnoughEvents_doesNothing() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"),
            createEvent(2, "Event2"),
            withUsage(createEvent(3, "Event3"), 200));
    Session session = Session.builder("id").events(events).build();

    // Retention size 5 > 3 events. Token usage 200 > threshold 100.
    TailRetentionEventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 5, 100);

    compactor.compact(session, mockSessionService).test().assertComplete();

    verify(mockSummarizer, never()).summarizeEvents(any());
    verify(mockSessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void compact_respectRetentionSize_summarizesCorrectEvents() {
    // Retention size is 2.
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"),
            createEvent(2, "Retain1"),
            withUsage(createEvent(3, "Retain2"), 200));
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent = createCompactedEvent(1, 1, "Summary", 4);

    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    // Token usage 200 > threshold 100.
    TailRetentionEventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 2, 100);

    compactor.compact(session, mockSessionService).test().assertComplete();

    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());
    List<Event> summarizedEvents = eventListCaptor.getValue();
    assertThat(summarizedEvents).hasSize(1);
    assertThat(getPromptText(summarizedEvents.get(0))).isEqualTo("Event1");

    verify(mockSessionService).appendEvent(eq(session), eq(compactedEvent));
  }

  @Test
  public void compact_withRetainedEventsPhysicallyBeforeCompaction_includesThem() {
    // Simulating the user's specific case with retention size 1:
    // "event1, event2, event3, compaction1-2 ... event3 is retained so it is before compaction
    // event"
    //
    // Timeline:
    // T=1: E1
    // T=2: E2
    // T=3: E3
    // T=4: C1 (Covers T=1 to T=2).
    //
    // Note: C1 was inserted *after* E3 in the list.
    // List order: E1, E2, E3, C1.
    //
    // If we have more events:
    // T=5: E5
    // T=6: E6
    //
    // Retained: E6.
    // Summary Input: C1, E3, E5. (E1, E2 covered by C1).
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "E1"),
            createEvent(2, "E2"),
            createEvent(3, "E3"),
            createCompactedEvent(
                /* startTimestamp= */ 1, /* endTimestamp= */ 2, "C1", /* eventTimestamp= */ 4),
            createEvent(5, "E5"),
            withUsage(createEvent(6, "E6"), 200));
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent = createCompactedEvent(1, 5, "Summary C1-E5", 7);

    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    // Token usage 200 > threshold 100.
    TailRetentionEventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 1, 100);

    compactor.compact(session, mockSessionService).test().assertComplete();

    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());
    List<Event> summarizedEvents = eventListCaptor.getValue();
    assertThat(summarizedEvents).hasSize(3);

    // Check first event is reconstructed C1
    Event reconstructedC1 = summarizedEvents.get(0);
    assertThat(getPromptText(reconstructedC1)).isEqualTo("C1");
    // Verify timestamp is reset to startTimestamp (1)
    assertThat(reconstructedC1.timestamp()).isEqualTo(1);

    // Check second event is E3
    Event e3 = summarizedEvents.get(1);
    assertThat(getPromptText(e3)).isEqualTo("E3");
    assertThat(e3.timestamp()).isEqualTo(3);

    // Check third event is E5
    Event e5 = summarizedEvents.get(2);
    assertThat(getPromptText(e5)).isEqualTo("E5");
    assertThat(e5.timestamp()).isEqualTo(5);
  }

  @Test
  public void compact_withMultipleCompactionEvents_respectsCompactionBoundary() {
    // T=1: E1
    // T=2: E2, retained by C1
    // T=3: E3, retained by C1
    // T=4: E4, retained by C1 and C2
    // T=5: C1 (Covers T=1)
    // T=6: E6, retained by C2
    // T=7: E7, retained by C2
    // T=8: C2 (Covers T=1 to T=3) since it covers C1 which starts at T=1.
    // T=9: E9

    // Retention = 3.
    // Expected to summarize: C2, E4. (E1 covered by C1 - ignored, E2, E3 covered by C2).
    // E6, E7, E9 are retained.

    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "E1"),
            createEvent(2, "E2"),
            createEvent(3, "E3"),
            createEvent(4, "E4"),
            createCompactedEvent(
                /* startTimestamp= */ 1, /* endTimestamp= */ 1, "C1", /* eventTimestamp= */ 5),
            createEvent(6, "E6"),
            createEvent(7, "E7"),
            createCompactedEvent(
                /* startTimestamp= */ 1, /* endTimestamp= */ 3, "C2", /* eventTimestamp= */ 8),
            withUsage(createEvent(9, "E9"), 200));
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent = createCompactedEvent(1, 4, "Summary C2-E4", 10);

    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    // Token usage 200 > threshold 100.
    TailRetentionEventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 3, 100);

    compactor.compact(session, mockSessionService).test().assertComplete();

    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());
    List<Event> summarizedEvents = eventListCaptor.getValue();

    assertThat(summarizedEvents).hasSize(2);

    // Check first event is reconstructed C2
    Event reconstructedC2 = summarizedEvents.get(0);
    assertThat(getPromptText(reconstructedC2)).isEqualTo("C2");
    // Verify timestamp is reset to startTimestamp (1), not event timestamp (8) or end timestamp (3)
    assertThat(reconstructedC2.timestamp()).isEqualTo(1);

    // Check second event is E4
    Event e4 = summarizedEvents.get(1);
    assertThat(e4.timestamp()).isEqualTo(4);
  }

  private static Event createEvent(long timestamp, String text) {
    return Event.builder()
        .timestamp(timestamp)
        .content(Content.builder().parts(Part.fromText(text)).build())
        .build();
  }

  private static String getPromptText(Event event) {
    return event
        .content()
        .flatMap(Content::parts)
        .flatMap(parts -> parts.stream().findFirst())
        .flatMap(Part::text)
        .orElseThrow();
  }

  private Event withUsage(Event event, int tokens) {
    return event.toBuilder()
        .usageMetadata(
            GenerateContentResponseUsageMetadata.builder().promptTokenCount(tokens).build())
        .build();
  }

  private Event createCompactedEvent(
      long startTimestamp, long endTimestamp, String content, long eventTimestamp) {
    return Event.builder()
        .timestamp(eventTimestamp)
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
