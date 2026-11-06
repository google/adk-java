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
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The answer to a {@link Choice} question. */
@Experimental
@AutoValue
@JsonDeserialize(using = ClassifierJson.ChoiceAnswerDeserializer.class)
public abstract non-sealed class ChoiceAnswer implements Answer {

  ChoiceAnswer() {}

  /** Returns the most likely label. */
  public abstract String choice();

  /** Returns each label of the question mapped to its probability, in [0, 1]. */
  public abstract ImmutableMap<String, Double> probabilities();

  /** Returns the provider's confidence in the answer, in [0, 1], if it reports one. */
  public abstract Optional<Double> confidence();

  public static Builder builder() {
    return new AutoValue_ChoiceAnswer.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link ChoiceAnswer}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder choice(String choice);

    @CanIgnoreReturnValue
    public abstract Builder probabilities(Map<String, Double> probabilities);

    @CanIgnoreReturnValue
    public abstract Builder confidence(@Nullable Double confidence);

    abstract ChoiceAnswer autoBuild();

    /**
     * Builds the answer.
     *
     * @throws IllegalStateException if the choice or the probabilities are not set
     * @throws IllegalArgumentException if a probability or the confidence is outside [0, 1]
     */
    public final ChoiceAnswer build() {
      ChoiceAnswer answer = autoBuild();
      ClassifierValidation.checkProbabilities(
          "Each ChoiceAnswer probability", answer.probabilities());
      ClassifierValidation.checkConfidence(answer.confidence());
      return answer;
    }
  }
}
