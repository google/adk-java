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

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Classifier}'s answer to a {@link Question}: a {@link ChoiceAnswer}, a {@link
 * BinaryAnswer}, a {@link ScoreAnswer}, or a {@link RefusalAnswer}. The ADK object mapper reads and
 * writes answers in Python ADK's JSON form, which names each kind in a {@code type} field.
 */
@Experimental
@JsonSerialize(using = ClassifierJson.AnswerSerializer.class)
@JsonDeserialize(using = ClassifierJson.AnswerDeserializer.class)
public sealed interface Answer {

  /** The answer to a {@link Question.Choice} question. */
  @AutoValue
  @JsonDeserialize(using = ClassifierJson.ChoiceAnswerDeserializer.class)
  abstract non-sealed class ChoiceAnswer implements Answer {

    ChoiceAnswer() {}

    /** Returns the most likely label. */
    public abstract String choice();

    /** Returns each label of the question mapped to its probability, in [0, 1]. */
    public abstract ImmutableMap<String, Double> probabilities();

    /** Returns the provider's confidence in the answer, in [0, 1], if it reports one. */
    public abstract Optional<Double> confidence();

    public static Builder builder() {
      return new AutoValue_Answer_ChoiceAnswer.Builder();
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

  /** The answer to a {@link Question.Binary} question. */
  @AutoValue
  @JsonDeserialize(using = ClassifierJson.BinaryAnswerDeserializer.class)
  abstract non-sealed class BinaryAnswer implements Answer {

    BinaryAnswer() {}

    /** Returns the probability that the statement is true, in [0, 1]. */
    public abstract double probability();

    /** Returns the provider's confidence in the answer, in [0, 1], if it reports one. */
    public abstract Optional<Double> confidence();

    public static Builder builder() {
      return new AutoValue_Answer_BinaryAnswer.Builder();
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

  /** The answer to a {@link Question.Score} question. */
  @AutoValue
  @JsonDeserialize(using = ClassifierJson.ScoreAnswerDeserializer.class)
  abstract non-sealed class ScoreAnswer implements Answer {

    ScoreAnswer() {}

    /** Returns the expected rubric level, weighted by {@link #probabilities()}. */
    public abstract double score();

    /** Returns each rubric level, counting from 0, mapped to its probability, in [0, 1]. */
    public abstract ImmutableMap<Integer, Double> probabilities();

    /** Returns the provider's confidence in the answer, in [0, 1], if it reports one. */
    public abstract Optional<Double> confidence();

    public static Builder builder() {
      return new AutoValue_Answer_ScoreAnswer.Builder();
    }

    public abstract Builder toBuilder();

    /** Builder for {@link ScoreAnswer}. */
    @AutoValue.Builder
    public abstract static class Builder {

      @CanIgnoreReturnValue
      public abstract Builder score(double score);

      @CanIgnoreReturnValue
      public abstract Builder probabilities(Map<Integer, Double> probabilities);

      @CanIgnoreReturnValue
      public abstract Builder confidence(@Nullable Double confidence);

      abstract ScoreAnswer autoBuild();

      /**
       * Builds the answer.
       *
       * @throws IllegalStateException if the score or the probabilities are not set
       * @throws IllegalArgumentException if the score is not finite, or if a probability or the
       *     confidence is outside [0, 1]
       */
      public final ScoreAnswer build() {
        ScoreAnswer answer = autoBuild();
        checkArgument(
            Double.isFinite(answer.score()), "ScoreAnswer score must be a finite number.");
        ClassifierValidation.checkProbabilities(
            "Each ScoreAnswer probability", answer.probabilities());
        ClassifierValidation.checkConfidence(answer.confidence());
        return answer;
      }
    }
  }

  /**
   * The answer to a question that the provider declined to answer. Refusals are reported per
   * question, so one {@link Classifier#classify} call can still answer the other questions.
   */
  @JsonDeserialize(using = ClassifierJson.RefusalAnswerDeserializer.class)
  enum RefusalAnswer implements Answer {
    INSTANCE
  }
}
