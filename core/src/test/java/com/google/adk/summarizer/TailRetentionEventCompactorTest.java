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

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TailRetentionEventCompactorTest {

  @Test
  public void getCompactionEvents_notEnoughEvents_returnsEmpty() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"), createEvent(2, "Event2"), createEvent(3, "Event3"));

    // Retention size 5 > 3 events
    TailRetentionEventCompactor.getCompactionEvents(events, 5)
        .test()
        .assertNoValues()
        .assertComplete();
  }

  @Test
  public void getCompactionEvents_respectRetentionSize() {
    // Retention size is 2.
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1, "Event1"), createEvent(2, "Retain1"), createEvent(3, "Retain2"));

    List<Event> result = TailRetentionEventCompactor.getCompactionEvents(events, 2).blockingGet();

    assertThat(result).hasSize(1);
    assertThat(getPromptText(result.get(0))).isEqualTo("Event1");
  }

  @Test
  public void getCompactionEvents_withRetainedEventsPhysicallyBeforeCompaction_includesThem() {
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
            createEvent(6, "E6"));

    List<Event> result = TailRetentionEventCompactor.getCompactionEvents(events, 1).blockingGet();

    assertThat(result).hasSize(3);

    // Check first event is reconstructed C1
    Event reconstructedC1 = result.get(0);
    assertThat(getPromptText(reconstructedC1)).isEqualTo("C1");
    // Verify timestamp is reset to startTimestamp (1)
    assertThat(reconstructedC1.timestamp()).isEqualTo(1);

    // Check second event is E3
    Event e3 = result.get(1);
    assertThat(getPromptText(e3)).isEqualTo("E3");
    assertThat(e3.timestamp()).isEqualTo(3);

    // Check third event is E5
    Event e5 = result.get(2);
    assertThat(getPromptText(e5)).isEqualTo("E5");
    assertThat(e5.timestamp()).isEqualTo(5);
  }

  @Test
  public void getCompactionEvents_withMultipleCompactionEvents_respectsCompactionBoundary() {
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
            createEvent(9, "E9"));

    List<Event> result = TailRetentionEventCompactor.getCompactionEvents(events, 3).blockingGet();

    assertThat(result).hasSize(2);

    // Check first event is reconstructed C2
    Event reconstructedC2 = result.get(0);
    assertThat(getPromptText(reconstructedC2)).isEqualTo("C2");
    // Verify timestamp is reset to startTimestamp (1), not event timestamp (8) or end timestamp (3)
    assertThat(reconstructedC2.timestamp()).isEqualTo(1);

    // Check second event is E4
    Event e4 = result.get(1);
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
