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

package com.google.adk.flows.llmflows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.BaseEventSummarizer;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CompactionTest {

  private InvocationContext context;
  private LlmRequest request;
  private Session session;
  private BaseSessionService sessionService;
  private BaseEventSummarizer summarizer;

  @Before
  public void setUp() {
    context = mock(InvocationContext.class);
    request = LlmRequest.builder().build();
    session = Session.builder("test-session").build();
    sessionService = mock(BaseSessionService.class);
    summarizer = mock(BaseEventSummarizer.class);

    when(context.session()).thenReturn(session);
    when(context.sessionService()).thenReturn(sessionService);
  }

  @Test
  public void processRequest_noConfig_doesNothing() {
    when(context.eventsCompactionConfig()).thenReturn(Optional.empty());

    Compaction compaction = new Compaction();
    compaction
        .processRequest(context, request)
        .test()
        .assertNoErrors()
        .assertValue(r -> r.updatedRequest() == request);

    verify(sessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void processRequest_withConfig_triggersCompaction() {
    // Setup config with threshold 100
    EventsCompactionConfig config = new EventsCompactionConfig(5, 1, summarizer, 100, 2);
    when(context.eventsCompactionConfig()).thenReturn(Optional.of(config));

    // Setup events with usage > 100 to trigger compaction
    Event event1 = mock(Event.class);
    Event event2 = mock(Event.class);
    Event event3 = mock(Event.class);
    when(event3.usageMetadata())
        .thenReturn(
            Optional.of(
                GenerateContentResponseUsageMetadata.builder().promptTokenCount(200).build()));

    session =
        Session.builder("test-session").events(ImmutableList.of(event1, event2, event3)).build();
    when(context.session()).thenReturn(session);

    // Summarizer mock
    Event summaryEvent = mock(Event.class);
    when(summarizer.summarizeEvents(any())).thenReturn(Maybe.just(summaryEvent));
    when(sessionService.appendEvent(any(), any())).thenReturn(Single.just(summaryEvent));

    Compaction compaction = new Compaction();
    compaction
        .processRequest(context, request)
        .test()
        .assertNoErrors()
        .assertValue(r -> r.updatedRequest() == request);

    // Verify compaction happened and result was appended
    verify(sessionService).appendEvent(eq(session), eq(summaryEvent));
  }

  @Test
  public void processRequest_withConfig_skipsCompactionIfBelowThreshold() {
    // Setup config with threshold 500
    EventsCompactionConfig config = new EventsCompactionConfig(5, 1, summarizer, 500, 2);
    when(context.eventsCompactionConfig()).thenReturn(Optional.of(config));

    // Setup events with usage 200 (below 500)
    Event event3 = mock(Event.class);
    when(event3.usageMetadata())
        .thenReturn(
            Optional.of(
                GenerateContentResponseUsageMetadata.builder().promptTokenCount(200).build()));

    session = Session.builder("test-session").events(ImmutableList.of(event3)).build();
    when(context.session()).thenReturn(session);

    Compaction compaction = new Compaction();
    compaction
        .processRequest(context, request)
        .test()
        .assertNoErrors()
        .assertValue(r -> r.updatedRequest() == request);

    // Verify NO compaction
    verify(sessionService, never()).appendEvent(any(), any());
  }

  @Test
  public void processRequest_withConfig_nullRetentionSize_doesNothing() {
    // Setup config with retentionSize = null
    EventsCompactionConfig config = new EventsCompactionConfig(5, 1, summarizer, 100, null);
    when(context.eventsCompactionConfig()).thenReturn(Optional.of(config));

    Compaction compaction = new Compaction();
    compaction
        .processRequest(context, request)
        .test()
        .assertNoErrors()
        .assertValue(r -> r.updatedRequest() == request);

    // Verify NO compaction and session.events() is not called
    verify(sessionService, never()).appendEvent(any(), any());
    verify(context, never()).session();
  }
}
