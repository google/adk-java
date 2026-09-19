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

import io.reactivex.rxjava3.core.Single;

/**
 * Extension point for the "judge" step of the ReasoningBank loop: an LLM-as-a-judge that
 * self-assesses whether a trajectory succeeded or failed.
 *
 * <p>The judge is intentionally <em>separate</em> from the {@link MemoryExtractor}: the verdict
 * gates which extraction prompt runs (success insights vs. preventative failure lessons) and must
 * be independently swappable (e.g. a ground-truth judge in tests) and testable.
 *
 * <p>The reference implementation biases the judge toward FAILURE when uncertain, because "a false
 * success is more harmful than a false failure" — a wrong success is distilled into a memory item
 * that then steers all future similar tasks. Implementations should preserve that asymmetry.
 *
 * <p>This module ships {@link LlmTrajectoryJudge}. Reference: Ouyang et al. "ReasoningBank: Scaling
 * Agent Self-Evolving with Reasoning Memory" (ICLR 2026, <a
 * href="https://arxiv.org/abs/2509.25140">arXiv:2509.25140</a>).
 */
public interface TrajectoryJudge {

  /**
   * Self-assesses a trajectory.
   *
   * @param query the task the trajectory attempted.
   * @param trajectory the executed trajectory.
   * @return a {@link Verdict}; never errors — an unavailable judge yields {@link
   *     Verdict.Outcome#INDETERMINATE} rather than a thrown exception.
   */
  Single<Verdict> judge(String query, ReasoningTrace trajectory);
}
