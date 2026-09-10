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

import com.google.auto.value.AutoValue;

/**
 * The outcome of a {@link TrajectoryJudge} self-assessment of one trajectory.
 *
 * <p>The three-state outcome deliberately diverges from the reference implementation's binary
 * success/failure verdict. A service (unlike a benchmark harness) must distinguish a judge that
 * <em>ran and decided failure</em> from a judge that <em>never produced a verdict</em> ({@link
 * Outcome#INDETERMINATE}): minting a preventative guardrail from a non-run would fabricate poison.
 */
@AutoValue
public abstract class Verdict {

  /** The self-assessed outcome of a trajectory. */
  public enum Outcome {
    /** The agent accomplished the task. Distill reusable success strategies. */
    SUCCESS,
    /**
     * The agent ran but did not accomplish the task (or the verdict was unparseable). Distill
     * preventative lessons. This is the asymmetric default when the judge is uncertain — a false
     * success is more harmful than a false failure because memory induction amplifies it.
     */
    FAILURE,
    /**
     * No verdict was produced (the judge errored, timed out, or returned no content). The caller
     * should abstain: persist the trace but mint no memory item.
     */
    INDETERMINATE
  }

  /** Returns the self-assessed outcome. */
  public abstract Outcome outcome();

  /** Returns the judge's reasoning ("Thoughts:"), propagated into the extracted memory item. */
  public abstract String rationale();

  /** Returns the judge's confidence in {@code [0, 1]}; {@code 0.0} when the model gives none. */
  public abstract double confidence();

  /** Creates a {@link Verdict}. */
  public static Verdict of(Outcome outcome, String rationale, double confidence) {
    return new AutoValue_Verdict(outcome, rationale, confidence);
  }
}
