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

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import com.google.adk.JsonBaseModel;
import com.google.adk.agents.Role;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.platform.UuidProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
  private final InstantSource instantSource;
  private final UuidProvider uuidProvider;

  public LlmEventSummarizer(BaseLlm baseLlm) {
    this(baseLlm, DEFAULT_PROMPT_TEMPLATE);
  }

  /**
   * Creates a summarizer with the given prompt template; {@code null} selects the default template.
   */
  public LlmEventSummarizer(BaseLlm baseLlm, @Nullable String promptTemplate) {
    this(baseLlm, promptTemplate, InstantSource.system(), UuidProvider.SYSTEM);
  }

  /**
   * Creates a summarizer whose compaction events draw their id, invocation id, and timestamp from
   * the given sources. Passing {@code null} as {@code promptTemplate} selects the default template.
   */
  public LlmEventSummarizer(
      BaseLlm baseLlm,
      @Nullable String promptTemplate,
      InstantSource instantSource,
      UuidProvider uuidProvider) {
    this.baseLlm = baseLlm;
    this.promptTemplate = promptTemplate != null ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
    this.instantSource = Objects.requireNonNull(instantSource, "instantSource cannot be null");
    this.uuidProvider = Objects.requireNonNull(uuidProvider, "uuidProvider cannot be null");
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
                        .role(Role.USER)
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
                                    .id(uuidProvider.newUuid())
                                    .timestamp(instantSource.instant().toEpochMilli())
                                    .author(Role.USER)
                                    .actions(EventActions.builder().compaction(compaction).build())
                                    .invocationId(uuidProvider.newUuid())
                                    .build())));
  }

  private String formatEventsForPrompt(List<Event> events) {
    return events.stream()
        .flatMap(
            event ->
                event.content().flatMap(Content::parts).stream()
                    .flatMap(List::stream)
                    .map(
                        part ->
                            formatPartForPrompt(
                                event.content().flatMap(Content::role).orElse(event.author()),
                                part))
                    .flatMap(Optional::stream))
        .collect(joining("\n"));
  }

  private String toJson(Object object) {
    try {
      return JsonBaseModel.getMapper().writeValueAsString(object);
    } catch (Exception e) {
      return String.valueOf(object);
    }
  }

  private Optional<String> formatPartForPrompt(String role, Part part) {
    return part.text()
        .filter(not(String::isEmpty))
        .map(t -> role + ": " + t)
        .or(() -> part.functionCall().map(f -> formatFunctionCallToString(role, f)))
        .or(() -> part.functionResponse().map(f -> formatFunctionResponseToString(role, f)));
  }

  private String formatFunctionCallToString(String role, FunctionCall f) {
    return String.format(
        "%s: [FUNCTION_CALL: %s(%s)]",
        role, f.name().orElse("unknown"), toJson(f.args().orElse(ImmutableMap.of())));
  }

  private String formatFunctionResponseToString(String role, FunctionResponse f) {
    return String.format(
        "%s: [FUNCTION_RESPONSE: %s -> %s]",
        role, f.name().orElse("unknown"), toJson(f.response().orElse(ImmutableMap.of())));
  }
}
