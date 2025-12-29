package com.google.adk.summarizer;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Optional;

/** An LLM-based event summarizer for sliding window compaction. */
public final class LlmEventSummarizer implements BaseEventSummarizer {

  private static final String DEFAULT_PROMPT_TEMPLATE =
      """
      The following is a conversation history between a user and an AI \
      agent. Please summarize the conversation, focusing on key \
      information and decisions made, as well as any unresolved \
      questions or tasks. The summary should be concise and capture the \
      essence of the interaction.

      {conversation_history}
      """;

  private final BaseLlm baseLlm;
  private final String promptTemplate;

  public LlmEventSummarizer(BaseLlm baseLlm) {
    this(baseLlm, DEFAULT_PROMPT_TEMPLATE);
  }

  public LlmEventSummarizer(BaseLlm baseLlm, String promptTemplate) {
    this.baseLlm = baseLlm;
    this.promptTemplate = promptTemplate;
  }

  @Override
  public Maybe<Event> summarizeEvents(List<Event> events) {
    if (events.isEmpty()) {
      return Maybe.empty();
    }

    String conversationHistory = formatEventsForPrompt(events);
    String prompt = promptTemplate.replace("{conversation_history}", conversationHistory);

    LlmRequest llmRequest =
        LlmRequest.builder()
            .model(baseLlm.model())
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(ImmutableList.of(Part.fromText(prompt)))
                        .build()))
            .build();

    return baseLlm
        .generateContent(llmRequest, false)
        .firstElement()
        .flatMap(
            llmResponse ->
                Maybe.fromOptional(
                    llmResponse
                        .content()
                        .map(content -> content.toBuilder().role("model").build())
                        .map(
                            summaryContent ->
                                EventCompaction.builder()
                                    .startTimestamp(events.get(0).timestamp())
                                    .endTimestamp(events.get(events.size() - 1).timestamp())
                                    .compactedContent(summaryContent)
                                    .build())
                        .map(
                            compaction ->
                                Event.builder()
                                    .author("user")
                                    .actions(EventActions.builder().compaction(compaction).build())
                                    .invocationId(Event.generateEventId())
                                    .build())));
  }

  private String formatEventsForPrompt(List<Event> events) {
    return events.stream()
        .flatMap(
            event ->
                event.content().flatMap(Content::parts).stream()
                    .flatMap(List::stream)
                    .map(Part::text)
                    .flatMap(Optional::stream)
                    .filter(not(String::isEmpty))
                    .map(text -> event.author() + ": " + text))
        .collect(joining("\\n"));
  }
}
