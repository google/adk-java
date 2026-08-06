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

package com.google.adk.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Default {@link MemoryExtractor} backed by an ADK {@link BaseLlm}.
 *
 * <p>Implements the "extract" step of the ReasoningBank loop with the reference implementation's
 * distillation prompts, generalized off WebArena. Routing follows the reference:
 *
 * <ul>
 *   <li>one successful trajectory &rarr; the success prompt (reusable strategies), capped at 3
 *       items;
 *   <li>one failed trajectory &rarr; the failure prompt (preventative lessons), capped at 3 items;
 *   <li>more than one trajectory &rarr; the parallel self-contrast prompt (MaTTS), capped at 5
 *       items.
 * </ul>
 *
 * <p>The cap is enforced in code, not merely requested in the prompt. Extraction runs off the
 * critical path (a fire-and-forget consolidation step), so this method never throws: a malformed or
 * over-cardinality model response yields an empty list rather than an exception.
 */
public final class LlmMemoryExtractor implements MemoryExtractor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final int SINGLE_TRAJECTORY_CAP = 3;
  private static final int PARALLEL_TRAJECTORY_CAP = 5;

  private static final String OUTPUT_FORMAT =
      """
      Respond as a JSON array of objects, each {"title": "<concise strategy name>", "description":\
       "<one sentence on when to use it>", "content": "<1-3 sentences of distilled insight>"}. Do\
       not embed specific names, queries, or literal values from this task.""";

  private static final String SUCCESSFUL_SI =
      """
      You are an expert agent. You are given a user query and the trajectory showing how an agent\
       SUCCESSFULLY accomplished the task.

      First think about WHY the trajectory succeeded, then summarize useful, generalizable insights\
       as memory items. Prefer concrete, actionable procedures over abstract principles. Extract at\
       most 3 items and do not repeat overlapping items.

      """
          + OUTPUT_FORMAT;

  private static final String FAILED_SI =
      """
      You are an expert agent. You are given a user query and the trajectory showing how an agent\
       attempted the task but FAILED.

      First REFLECT on WHY the trajectory failed, then summarize lessons and strategies to PREVENT\
       the failure as memory items. Prefer concrete, actionable recovery procedures; each item's\
       content should capture insights to avoid such failures on similar tasks. Extract at most 3\
       items and do not repeat overlapping items.

      """
          + OUTPUT_FORMAT;

  private static final String PARALLEL_SI =
      """
      You are an expert agent. You are given a user query and MULTIPLE trajectories (some\
       successful, some failed) that attempted the SAME task.

      Compare and CONTRAST the trajectories using self-contrast reasoning: what distinguishes the\
       successful approaches from the failed ones? Summarize the most robust, generalizable\
       strategies as memory items, including preventative lessons from the failures. Extract at most\
       5 items and do not repeat overlapping items.

      """
          + OUTPUT_FORMAT;

  private final BaseLlm llm;

  public LlmMemoryExtractor(BaseLlm llm) {
    this.llm = Objects.requireNonNull(llm, "llm");
  }

  @Override
  public Single<ImmutableList<ReasoningMemoryItem>> extract(
      String query, List<ReasoningTrace> trajectories) {
    if (trajectories.isEmpty()) {
      return Single.just(ImmutableList.of());
    }

    boolean parallel = trajectories.size() > 1;
    int cap = parallel ? PARALLEL_TRAJECTORY_CAP : SINGLE_TRAJECTORY_CAP;
    String systemPrompt =
        parallel ? PARALLEL_SI : (trajectories.get(0).successful() ? SUCCESSFUL_SI : FAILED_SI);

    // Provenance: a single trajectory's id and outcome flow onto every minted item; a parallel
    // distillation has no single source, so the items are contrastive strategies (not
    // failure-derived) with no source id.
    String sourceTraceId = parallel ? null : trajectories.get(0).id();
    boolean sourceSuccessful = parallel || trajectories.get(0).successful();
    String verdict = parallel ? null : (trajectories.get(0).successful() ? "SUCCESS" : "FAILURE");
    String idBase = sourceTraceId != null ? sourceTraceId : "mem";

    LlmRequest request =
        LlmRequest.builder()
            .model(llm.model())
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(
                            ImmutableList.of(
                                Part.fromText(formatTrajectories(query, trajectories))))
                        .build()))
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.fromText(systemPrompt)))
                            .build())
                    .temperature(1.0f)
                    .responseMimeType("application/json")
                    .build())
            .build();

    return llm.generateContent(request, false)
        .firstElement()
        .map(
            response ->
                parseItems(
                    LlmJsonSupport.extractText(response),
                    cap,
                    idBase,
                    sourceTraceId,
                    sourceSuccessful,
                    verdict))
        .switchIfEmpty(Single.just(ImmutableList.of()))
        .onErrorReturnItem(ImmutableList.of());
  }

  private static String formatTrajectories(String query, List<ReasoningTrace> trajectories) {
    StringBuilder sb = new StringBuilder();
    sb.append("User query: ").append(query).append("\n\n");
    for (int i = 0; i < trajectories.size(); i++) {
      ReasoningTrace t = trajectories.get(i);
      sb.append("Trajectory ")
          .append(i + 1)
          .append(" (outcome: ")
          .append(t.successful() ? "success" : "failure")
          .append("):\n");
      if (!t.reasoningSteps().isEmpty()) {
        for (String step : t.reasoningSteps()) {
          sb.append("- ").append(step).append('\n');
        }
      }
      if (t.metadata() != null && !t.metadata().isEmpty()) {
        sb.append("Notes: ").append(t.metadata()).append('\n');
      }
      sb.append("Final output: ").append(t.output()).append("\n\n");
    }
    return sb.toString();
  }

  /** Parses the model's JSON array into capped, provenance-tagged items. Never throws. */
  private static ImmutableList<ReasoningMemoryItem> parseItems(
      String text,
      int cap,
      String idBase,
      @Nullable String sourceTraceId,
      boolean sourceSuccessful,
      @Nullable String verdict) {
    String trimmed = text == null ? "" : text.strip();
    if (trimmed.isEmpty()) {
      return ImmutableList.of();
    }
    try {
      JsonNode root = MAPPER.readTree(LlmJsonSupport.stripCodeFence(trimmed));
      if (root == null || !root.isArray()) {
        return ImmutableList.of();
      }
      ImmutableList.Builder<ReasoningMemoryItem> out = ImmutableList.builder();
      int count = 0;
      for (JsonNode node : root) {
        if (count >= cap) {
          break;
        }
        String title = node.path("title").asText("").strip();
        String description = node.path("description").asText("").strip();
        String content = node.path("content").asText("").strip();
        if (title.isEmpty() || content.isEmpty()) {
          continue;
        }
        out.add(
            ReasoningMemoryItem.builder()
                .id(idBase + "-" + count)
                .title(title)
                .description(description)
                .content(content)
                .sourceTraceSuccessful(sourceSuccessful)
                .sourceTraceId(sourceTraceId)
                .judgeVerdict(verdict)
                .build());
        count++;
      }
      return out.build();
    } catch (Exception parseError) {
      return ImmutableList.of();
    }
  }
}
