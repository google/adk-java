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

package com.google.adk.classifiers;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The answer to a {@link Binary} question. */
@Experimental
@AutoValue
@JsonDeserialize(using = ClassifierJson.BinaryAnswerDeserializer.class)
public abstract non-sealed class BinaryAnswer implements Answer {

  BinaryAnswer() {}

  /** Returns the probability that the statement is true, in [0, 1]. */
  public abstract double probability();

  /** Returns the provider's confidence in the answer, in [0, 1], if it reports one. */
  public abstract Optional<Double> confidence();

  public static Builder builder() {
    return new AutoValue_BinaryAnswer.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link BinaryAnswer}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder probability(double probability);

    @CanIgnoreReturnValue
    public abstract Builder confidence(@Nullable Double confidence);

    abstract BinaryAnswer autoBuild();

    /**
     * Builds the answer.
     *
     * @throws IllegalStateException if the probability is not set
     * @throws IllegalArgumentException if the probability or the confidence is outside [0, 1]
     */
    public final BinaryAnswer build() {
      BinaryAnswer answer = autoBuild();
      ClassifierValidation.checkProbability("probability", answer.probability());
      ClassifierValidation.checkConfidence(answer.confidence());
      return answer;
    }
  }
}
