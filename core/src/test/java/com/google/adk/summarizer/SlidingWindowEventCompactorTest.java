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
import com.google.common.truth.Correspondence;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
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
public class SlidingWindowEventCompactorTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock private BaseSessionService mockSessionService;
  @Mock BaseEventSummarizer mockSummarizer;
  @Captor ArgumentCaptor<List<Event>> eventListCaptor;

  @Test
  public void compaction_noEvents() {
    EventCompactor compactor =
        new SlidingWindowEventCompactor(
            new EventsCompactionConfig(2, 1, Optional.of(mockSummarizer)));
    Session session = Session.builder("id").build();

    compactor.compact(session, mockSessionService).blockingSubscribe();
    verify(mockSessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void compaction_notEnoughInvocations() {
    EventCompactor compactor =
        new SlidingWindowEventCompactor(
            new EventsCompactionConfig(2, 1, Optional.of(mockSummarizer)));
    Session session =
        Session.builder("id")
            .events(ImmutableList.of(Event.builder().invocationId("1").build()))
            .build();

    compactor.compact(session, mockSessionService).blockingSubscribe();
    verify(mockSessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void compaction_firstCompaction() {
    EventCompactor compactor =
        new SlidingWindowEventCompactor(
            new EventsCompactionConfig(2, 1, Optional.of(mockSummarizer)));
    // Add 4 events without any compaction event
    ImmutableList<Event> events =
        ImmutableList.of(
            Event.builder().invocationId("1").timestamp(1).build(),
            Event.builder().invocationId("2").timestamp(2).build(),
            Event.builder().invocationId("3").timestamp(3).build(),
            Event.builder().invocationId("4").timestamp(4).build());
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent = createCompactedEvent(1, 4, "Summary 1-4");
    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    compactor.compact(session, mockSessionService).blockingSubscribe();
    // Even with the interval = 2 and overlap = 1, all 4 events should be included
    verify(mockSummarizer).summarizeEvents(eq(events));
    verify(mockSessionService).appendEvent(eq(session), eq(compactedEvent));
  }

  @Test
  public void compaction_withOverlap() {
    EventCompactor compactor =
        new SlidingWindowEventCompactor(
            new EventsCompactionConfig(2, 1, Optional.of(mockSummarizer)));
    // First 2 events are compacted, plus three uncompacted events
    ImmutableList<Event> events =
        ImmutableList.of(
            Event.builder().invocationId("1").timestamp(1).build(),
            Event.builder().invocationId("2").timestamp(2).build(),
            createCompactedEvent(1, 2, "Summary 1-2"),
            Event.builder().invocationId("3").timestamp(3).build(),
            Event.builder().invocationId("4").timestamp(4).build(),
            Event.builder().invocationId("5").timestamp(5).build());
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent = createCompactedEvent(2, 5, "Summary 2-5");
    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    compactor.compact(session, mockSessionService).blockingSubscribe();

    // Should include events 2-5.
    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());
    assertThat(eventListCaptor.getValue())
        .comparingElementsUsing(
            Correspondence.<Event, String>from(
                (actual, expected) -> actual.invocationId().equals(expected), ""))
        .containsExactly("2", "3", "4", "5");
    verify(mockSessionService).appendEvent(eq(session), eq(compactedEvent));
  }

  @Test
  public void compaction_multipleEventsWithSameInvocation() {
    EventCompactor compactor =
        new SlidingWindowEventCompactor(
            new EventsCompactionConfig(1, 1, Optional.of(mockSummarizer)));
    ImmutableList<Event> events =
        ImmutableList.of(
            Event.builder().invocationId("1").timestamp(1).build(),
            Event.builder().invocationId("1").timestamp(2).build(),
            createCompactedEvent(1, 2, "Summary 1"),
            Event.builder().invocationId("2").timestamp(3).build(),
            Event.builder().invocationId("2").timestamp(4).build());
    Session session = Session.builder("id").events(events).build();
    Event compactedEvent = createCompactedEvent(1, 4, "Summary 1-2");
    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.just(compactedEvent));
    when(mockSessionService.appendEvent(any(), any())).then(i -> Single.just(i.getArgument(1)));

    compactor.compact(session, mockSessionService).blockingSubscribe();

    // Should include invocations 1-2, with all 4 events.
    verify(mockSummarizer).summarizeEvents(eventListCaptor.capture());
    assertThat(eventListCaptor.getValue())
        .comparingElementsUsing(
            Correspondence.<Event, Long>from(
                (actual, expected) -> actual.timestamp() == expected, ""))
        .containsExactly(1L, 2L, 3L, 4L);

    verify(mockSessionService).appendEvent(eq(session), eq(compactedEvent));
  }

  @Test
  public void compaction_noCompactionEventFromSummarizer() {
    EventCompactor compactor =
        new SlidingWindowEventCompactor(
            new EventsCompactionConfig(1, 0, Optional.of(mockSummarizer)));
    ImmutableList<Event> events =
        ImmutableList.of(Event.builder().invocationId("1").timestamp(1).build());
    Session session = Session.builder("id").events(events).build();
    when(mockSummarizer.summarizeEvents(any())).thenReturn(Maybe.empty());

    compactor.compact(session, mockSessionService).blockingSubscribe();

    // The summarizer should get called since interval = 1
    verify(mockSummarizer).summarizeEvents(eq(events));
    // No compaction event produced since the summarize returns empty.
    verify(mockSessionService, never()).appendEvent(any(), any());
  }

  private Event createCompactedEvent(long startTimestamp, long endTimestamp, String content) {
    return Event.builder()
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
