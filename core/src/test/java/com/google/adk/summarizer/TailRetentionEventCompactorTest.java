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

package com.google.adk.summarizer;

import static com.google.common.truth.Truth.assertThat;
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
  @Mock BaseEventSummarizer mockSummarizer;
  @Captor ArgumentCaptor<List<Event>> eventListCaptor;

  @Test
  public void compaction_notEnoughEvents() {
    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 5);
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"), createEvent(2, "Event2"), createEvent(3, "Event3"));
    Session session = Session.builder("id").events(events).build();

    compactor.compact(session, mockSessionService).blockingSubscribe();
    verify(mockSessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void compaction_respectRetentionSize() {
    // Retention size is 2.
    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 2);
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"), createEvent(2, "Retain1"), createEvent(3, "Retain2"));
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent =
        createCompactedEvent(
            /* startTimestamp= */ 1, /* endTimestamp= */ 1, "C1", /* eventTimestamp= */ 4);
    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    compactor.compact(session, mockSessionService).blockingSubscribe();

    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());

    List<Event> capturedEvents = eventListCaptor.getValue();
    assertPromptDoesNotContain(capturedEvents, "Retain");
    verify(mockSessionService).appendEvent(eq(session), eq(compactedEvent));
    assertThat(capturedEvents).hasSize(1);
    assertThat(getPromptText(capturedEvents.get(0))).isEqualTo("Event1");
  }

  @Test
  public void call_withRetainedEventsPhysicallyBeforeCompaction_includesThem() {
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
    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 1);
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Compacted1"),
            createEvent(2, "Compacted2"),
            createEvent(3, "Event3"),
            createCompactedEvent(
                /* startTimestamp= */ 1, /* endTimestamp= */ 2, "C1", /* eventTimestamp= */ 4),
            createEvent(5, "Event5"),
            createEvent(6, "Retained6"));
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent =
        createCompactedEvent(
            /* startTimestamp= */ 1, /* endTimestamp= */ 5, "C2", /* eventTimestamp= */ 7);
    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    compactor.compact(session, mockSessionService).blockingSubscribe();

    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());

    List<Event> capturedEvents = eventListCaptor.getValue();

    assertPromptDoesNotContain(capturedEvents, "Retained");
    assertPromptDoesNotContain(capturedEvents, "Compacted");
    verify(mockSessionService).appendEvent(eq(session), eq(compactedEvent));

    // Check size
    assertThat(capturedEvents).hasSize(3);

    // Check first event is reconstructed C1
    Event reconstructedC1 = capturedEvents.get(0);
    assertThat(getPromptText(reconstructedC1)).isEqualTo("C1");
    // Verify timestamp is reset to startTimestamp (1)
    assertThat(reconstructedC1.timestamp()).isEqualTo(1);

    // Check second event is E3
    Event e3 = capturedEvents.get(1);
    assertThat(e3.timestamp()).isEqualTo(3);

    // Check third event is E5
    Event e5 = capturedEvents.get(2);
    assertThat(e5.timestamp()).isEqualTo(5);
  }

  @Test
  public void compaction_withMultipleCompactionEvents_respectsCompactionBoundary() {
    // T=1: E1
    // T=2: E2, retained by C1
    // T=3: E3, retained by C1
    // T=4: E4, retained by C1 and C2
    // T=5: C1 (Covers T=1)
    // T=6: E6, retained by C2 and C3
    // T=7: E7, retained by C2 and C3
    // T=8: C2 (Covers T=1 to T=3) since it covers C1 which starts at T=1.
    // T=9: E9, retained by C3

    // Retention = 3.
    // Expected to summarize: C2, E4. (E1 covered by C1 - ignored, E2, E3 covered by C2).
    // E6, E7, E9 are retained.

    EventCompactor compactor = new TailRetentionEventCompactor(mockSummarizer, 3);
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
            createEvent(9, "E9"));
    Session session = Session.builder("id").events(events).build();

    // We expect the summary to be of C2 and E4.
    // But note that getCompactionEvents reconstructs C2 from its compacted content.
    // And E4 is passed as is.
    Event compactedResult =
        createCompactedEvent(
            /* startTimestamp= */ 1,
            /* endTimestamp= */ 4,
            "Summary C2+E4",
            /* eventTimestamp= */ 10);
    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedResult));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    compactor.compact(session, mockSessionService).blockingSubscribe();

    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());
    List<Event> capturedEvents = eventListCaptor.getValue();

    // Check size
    assertThat(capturedEvents).hasSize(2);

    // Check first event is reconstructed C2
    Event reconstructedC2 = capturedEvents.get(0);
    assertThat(getPromptText(reconstructedC2)).isEqualTo("C2");
    // Verify timestamp is reset to startTimestamp (1), not event timestamp (8) or end timestamp (3)
    assertThat(reconstructedC2.timestamp()).isEqualTo(1);

    // Check second event is E4
    Event e4 = capturedEvents.get(1);
    assertThat(e4.timestamp()).isEqualTo(4);

    verify(mockSessionService).appendEvent(eq(session), eq(compactedResult));
  }

  private static Event createEvent(long timestamp, String text) {
    return Event.builder()
        .timestamp(timestamp)
        .content(Content.builder().parts(Part.fromText(text)).build())
        .build();
  }

  private void assertPromptDoesNotContain(List<Event> capturedEvents, String... unexpected) {
    for (Event event : capturedEvents) {
      String promptText = getPromptText(event);
      for (String s : unexpected) {
        assertThat(promptText).doesNotContain(s);
      }
    }
  }

  private static String getPromptText(Event event) {
    return event
        .content()
        .flatMap(Content::parts)
        .flatMap(parts -> parts.stream().findFirst())
        .flatMap(Part::text)
        .orElseThrow();
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
