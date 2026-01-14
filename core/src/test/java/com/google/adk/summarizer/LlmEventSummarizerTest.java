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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.HashMap;
import org.junit.Before;
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
public class LlmEventSummarizerTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock private BaseLlm mockLlm;
  private LlmEventSummarizer summarizer;
  @Captor private ArgumentCaptor<LlmRequest> llmRequestCaptor;

  private static final String DEFAULT_PROMPT_TEMPLATE =
      """
      The following is a conversation history between a user and an AI \
      agent. Please summarize the conversation, focusing on key \
      information and decisions made, as well as any unresolved \
      questions or tasks. The summary should be concise and capture the \
      essence of the interaction.

      {conversation_history}
      """;

  @Before
  public void setUp() {
    summarizer = new LlmEventSummarizer(mockLlm);
    when(mockLlm.model()).thenReturn("test-model");
  }

  @Test
  public void summarizeEvents_success() {
    ImmutableList<Event> events =
        ImmutableList.of(createEvent(1L, "Hello", "user"), createEvent(2L, "Hi there!", "model"));
    String expectedConversationHistory = "user: Hello\\nmodel: Hi there!";
    String expectedPrompt =
        DEFAULT_PROMPT_TEMPLATE.replace("{conversation_history}", expectedConversationHistory);
    LlmResponse mockLlmResponse =
        LlmResponse.builder()
            .content(Content.builder().parts(ImmutableList.of(Part.fromText("Summary"))).build())
            .build();

    when(mockLlm.generateContent(any(LlmRequest.class), eq(false)))
        .thenReturn(Flowable.just(mockLlmResponse));

    Event compactedEvent = summarizer.summarizeEvents(events).blockingGet();

    assertThat(compactedEvent).isNotNull();
    assertThat(
            compactedEvent
                .actions()
                .compaction()
                .get()
                .compactedContent()
                .parts()
                .get()
                .get(0)
                .text())
        .hasValue("Summary");
    assertThat(compactedEvent.author()).isEqualTo("user");
    assertThat(compactedEvent.actions()).isNotNull();
    assertThat(compactedEvent.actions().compaction()).isPresent();
    assertThat(compactedEvent.actions().compaction().get().startTimestamp()).isEqualTo(1L);
    assertThat(compactedEvent.actions().compaction().get().endTimestamp()).isEqualTo(2L);

    verify(mockLlm).generateContent(llmRequestCaptor.capture(), eq(false));
    LlmRequest llmRequest = llmRequestCaptor.getValue();
    assertThat(llmRequest).isNotNull();
    assertThat(llmRequest.model()).hasValue("test-model");
    assertThat(llmRequest.contents().get(0).role()).hasValue("user");
    assertThat(llmRequest.contents().get(0).parts().get().get(0).text()).hasValue(expectedPrompt);
  }

  @Test
  public void summarizeEvents_emptyLlmResponse() {
    ImmutableList<Event> events = ImmutableList.of(createEvent(1L, "Hello", "user"));
    LlmResponse mockLlmResponse = LlmResponse.builder().build(); // No content

    when(mockLlm.generateContent(any(LlmRequest.class), eq(false)))
        .thenReturn(Flowable.just(mockLlmResponse));

    summarizer.summarizeEvents(events).test().assertNoValues();
  }

  @Test
  public void summarizeEvents_emptyInput() {
    summarizer.summarizeEvents(ImmutableList.of()).test().assertNoValues();
    verifyNoInteractions(mockLlm);
  }

  @Test
  public void summarizeEvents_formatsEventsForPromptCorrectly() {
    ImmutableList<Event> events =
        ImmutableList.of(
            createEvent(1L, "User says...", "user"),
            createEvent(2L, "Model replies...", "model"),
            createEvent(3L, "Another user input", "user"),
            createEvent(4L, "More model text", "model"),
            // Event with no content
            Event.builder().timestamp(5L).author("user").invocationId("id1").build(),
            // Event with empty content part
            Event.builder()
                .timestamp(6L)
                .author("model")
                .content(Content.builder().parts(ImmutableList.of(Part.fromText(""))).build())
                .invocationId("id2")
                .build(),
            // Event with function call
            Event.builder()
                .timestamp(7L)
                .author("model")
                .content(
                    Content.builder()
                        .parts(
                            ImmutableList.of(
                                Part.builder()
                                    .functionCall(
                                        FunctionCall.builder()
                                            .name("tool")
                                            .args(new HashMap<>())
                                            .build())
                                    .build()))
                        .build())
                .invocationId("id3")
                .build(),
            // Event with function response
            Event.builder()
                .timestamp(8L)
                .author("model")
                .content(
                    Content.builder()
                        .parts(
                            ImmutableList.of(
                                Part.builder()
                                    .functionResponse(
                                        FunctionResponse.builder()
                                            .name("tool")
                                            .response(new HashMap<>())
                                            .build())
                                    .build()))
                        .build())
                .invocationId("id4")
                .build());

    String expectedFormattedHistory =
        "user: User says...\\n"
            + "model: Model replies...\\n"
            + "user: Another user input\\n"
            + "model: More model text";
    String expectedPrompt =
        DEFAULT_PROMPT_TEMPLATE.replace("{conversation_history}", expectedFormattedHistory);

    LlmResponse mockLlmResponse =
        LlmResponse.builder()
            .content(Content.builder().parts(ImmutableList.of(Part.fromText("Summary"))).build())
            .build();

    when(mockLlm.generateContent(any(LlmRequest.class), eq(false)))
        .thenReturn(Flowable.just(mockLlmResponse));

    var unused = summarizer.summarizeEvents(events).blockingGet();

    verify(mockLlm).generateContent(llmRequestCaptor.capture(), eq(false));
    LlmRequest llmRequest = llmRequestCaptor.getValue();
    assertThat(llmRequest.contents().get(0).parts().get().get(0).text()).hasValue(expectedPrompt);
  }

  private Event createEvent(long timestamp, String text, String author) {
    return Event.builder()
        .timestamp(timestamp)
        .author(author)
        .content(Content.builder().parts(ImmutableList.of(Part.fromText(text))).build())
        .invocationId(Event.generateEventId())
        .build();
  }
}
