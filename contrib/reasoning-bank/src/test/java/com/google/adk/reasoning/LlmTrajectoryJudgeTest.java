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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.reasoning.Verdict.Outcome;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LlmTrajectoryJudge}. */
@RunWith(JUnit4.class)
public final class LlmTrajectoryJudgeTest {

  private static ReasoningTrace trace() {
    return ReasoningTrace.builder()
        .id("t1")
        .task("Find the cheapest direct flight")
        .output("Found a direct flight for $200")
        .build();
  }

  private static Verdict judge(FakeLlm llm) {
    return new LlmTrajectoryJudge(llm)
        .judge("Find the cheapest direct flight", trace())
        .blockingGet();
  }

  @Test
  public void judge_successStatus_returnsSuccess() {
    Verdict verdict =
        judge(
            FakeLlm.returningText("{\"thoughts\":\"all constraints met\",\"status\":\"success\"}"));

    assertThat(verdict.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(verdict.rationale()).contains("constraints met");
  }

  @Test
  public void judge_failureStatus_returnsFailure() {
    Verdict verdict =
        judge(
            FakeLlm.returningText(
                "{\"thoughts\":\"reported an unverified price\",\"status\":\"failure\"}"));

    assertThat(verdict.outcome()).isEqualTo(Outcome.FAILURE);
  }

  @Test
  public void judge_ranButUnparseable_defaultsToFailure() {
    // The judge ran and produced output we cannot parse into a verdict. The asymmetric default is
    // FAILURE (a false success poisons future behavior) -- never INDETERMINATE.
    Verdict verdict = judge(FakeLlm.returningText("Yeah the agent totally nailed it!"));

    assertThat(verdict.outcome()).isEqualTo(Outcome.FAILURE);
  }

  @Test
  public void judge_llmErrors_isIndeterminate() {
    // The judge never produced a verdict; abstaining (mint nothing) is correct, not a fabricated
    // FAILURE guardrail.
    Verdict verdict = judge(FakeLlm.erroring(new RuntimeException("503 unavailable")));

    assertThat(verdict.outcome()).isEqualTo(Outcome.INDETERMINATE);
  }

  @Test
  public void judge_emptyResponse_isIndeterminate() {
    Verdict verdict = judge(FakeLlm.returningNothing());

    assertThat(verdict.outcome()).isEqualTo(Outcome.INDETERMINATE);
  }

  @Test
  public void judge_codeFencedJson_isParsed() {
    Verdict verdict =
        judge(FakeLlm.returningText("```json\n{\"thoughts\":\"ok\",\"status\":\"success\"}\n```"));

    assertThat(verdict.outcome()).isEqualTo(Outcome.SUCCESS);
  }
}
