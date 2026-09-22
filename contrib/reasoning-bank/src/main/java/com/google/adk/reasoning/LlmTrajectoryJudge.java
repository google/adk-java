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
import java.util.Locale;
import java.util.Objects;

/**
 * Default {@link TrajectoryJudge} backed by an ADK {@link BaseLlm}.
 *
 * <p>The judge prompt is the reference implementation's asymmetric-strictness rubric, generalized
 * off WebArena's web-navigation task types so it applies to any agent task. It asks the model to
 * verify completeness, grounding, and right-target before declaring success, and to "mark failure
 * when uncertain, because a false success is more harmful than a false failure."
 *
 * <p>Verdict mapping:
 *
 * <ul>
 *   <li>parsed {@code status == "success"} &rarr; {@link Verdict.Outcome#SUCCESS}
 *   <li>parsed any other status, or non-empty but unparseable output &rarr; {@link
 *       Verdict.Outcome#FAILURE} (the asymmetric default)
 *   <li>the model errored, timed out, or returned no content &rarr; {@link
 *       Verdict.Outcome#INDETERMINATE} (abstain; mint nothing)
 * </ul>
 */
public final class LlmTrajectoryJudge implements TrajectoryJudge {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String SYSTEM_PROMPT =
      """
      You are an expert evaluator of an autonomous agent's task execution. Given the user's query,\
       the agent's reasoning/action history, and the agent's final output, decide whether the\
       execution succeeded or failed.

      Before calling a task successful, verify all three:
      - Completeness: every constraint in the query is satisfied, and if the task implies an\
       exhaustive result (a list, a range, an aggregate), the agent inspected the full source.
      - Grounding: every value, name, or URL the agent reports is traceable to a specific\
       observation; values that were inferred, guessed, or summarized without a visible source\
       count as failures.
      - Right target: when the query names a specific entity, confirm the agent acted on that exact\
       entity and not an adjacent one.
      When uncertain on any of these, mark failure. A false success is more harmful than a false\
       failure, because memory induction amplifies it into future behavior.

      Respond as a single JSON object: {"thoughts": "<your reasoning>", "status": "success" or\
       "failure"}.
      """;

  private final BaseLlm llm;

  public LlmTrajectoryJudge(BaseLlm llm) {
    this.llm = Objects.requireNonNull(llm, "llm");
  }

  @Override
  public Single<Verdict> judge(String query, ReasoningTrace trajectory) {
    LlmRequest request =
        LlmRequest.builder()
            .model(llm.model())
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(ImmutableList.of(Part.fromText(formatTrajectory(query, trajectory))))
                        .build()))
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.fromText(SYSTEM_PROMPT)))
                            .build())
                    .temperature(0.0f)
                    .responseMimeType("application/json")
                    .build())
            .build();

    return llm.generateContent(request, false)
        .firstElement()
        .map(response -> parseVerdict(LlmJsonSupport.extractText(response)))
        .switchIfEmpty(
            Single.just(
                Verdict.of(Verdict.Outcome.INDETERMINATE, "judge returned no content", 0.0)))
        .onErrorReturn(
            error ->
                Verdict.of(
                    Verdict.Outcome.INDETERMINATE,
                    "judge call failed: " + error.getMessage(),
                    0.0));
  }

  private static String formatTrajectory(String query, ReasoningTrace trajectory) {
    StringBuilder sb = new StringBuilder();
    sb.append("User query: ").append(query).append("\n\n");
    if (!trajectory.reasoningSteps().isEmpty()) {
      sb.append("Agent reasoning/action history:\n");
      for (String step : trajectory.reasoningSteps()) {
        sb.append("- ").append(step).append('\n');
      }
      sb.append('\n');
    }
    sb.append("Agent's final output: ").append(trajectory.output());
    return sb.toString();
  }

  /** Maps non-empty model text to a SUCCESS/FAILURE verdict; empty text to INDETERMINATE. */
  private static Verdict parseVerdict(String text) {
    String trimmed = text == null ? "" : text.strip();
    if (trimmed.isEmpty()) {
      return Verdict.of(Verdict.Outcome.INDETERMINATE, "judge returned empty output", 0.0);
    }
    String json = LlmJsonSupport.stripCodeFence(trimmed);
    try {
      JsonNode node = MAPPER.readTree(json);
      String status = node.path("status").asText("").trim().toLowerCase(Locale.ROOT);
      String thoughts = node.path("thoughts").asText("");
      if (status.equals("success")) {
        return Verdict.of(
            Verdict.Outcome.SUCCESS, thoughts.isEmpty() ? "judged success" : thoughts, 0.0);
      }
      if (status.equals("failure")) {
        return Verdict.of(
            Verdict.Outcome.FAILURE, thoughts.isEmpty() ? "judged failure" : thoughts, 0.0);
      }
      // Parsed, but no recognizable status: asymmetric default.
      return Verdict.of(Verdict.Outcome.FAILURE, "malformed judge verdict: " + trimmed, 0.0);
    } catch (Exception parseError) {
      // Ran but produced unparseable output: asymmetric default (never INDETERMINATE).
      return Verdict.of(Verdict.Outcome.FAILURE, "unparseable judge output: " + trimmed, 0.0);
    }
  }
}
