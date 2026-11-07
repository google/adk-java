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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A typed question that a {@link Classifier} answers: a {@link Choice}, a {@link Binary}, or a
 * {@link Score}. Its instructions and criteria are {@link ClassifierContent}: text, or a JSON
 * object or array.
 */
@Experimental
public sealed interface Question {

  /** Returns what to decide, if set. */
  Optional<ClassifierContent> instructions();

  /**
   * Asks a {@link Classifier} to pick one of two or more labels, answered by a {@link
   * Answer.ChoiceAnswer}.
   */
  @AutoValue
  abstract non-sealed class Choice implements Question {

    Choice() {}

    @Override
    public abstract Optional<ClassifierContent> instructions();

    /**
     * Returns each label, in the order it was added, mapped to a description of when it applies, or
     * to null for none. There are at least two labels.
     */
    // Descriptions may be null, which ImmutableMap rejects; the map is unmodifiable.
    @SuppressWarnings("AutoValueImmutableFields")
    public abstract Map<String, @Nullable ClassifierContent> criteria();

    public static Builder builder() {
      return new AutoValue_Question_Choice.Builder();
    }

    public abstract Builder toBuilder();

    /** Builder for {@link Choice}. */
    @AutoValue.Builder
    public abstract static class Builder {

      @CanIgnoreReturnValue
      public final Builder instructions(@Nullable ClassifierContent instructions) {
        return setInstructions(instructions);
      }

      /**
       * Sets plain-text instructions; to clear them, pass null to {@link
       * #instructions(ClassifierContent)}.
       */
      @CanIgnoreReturnValue
      public final Builder instructions(String instructions) {
        return setInstructions(new ClassifierContent.Text(instructions));
      }

      /**
       * Sets every label and its description, replacing any previously set labels. The labels keep
       * the map's iteration order.
       */
      @CanIgnoreReturnValue
      public final Builder criteria(Map<String, ? extends @Nullable ClassifierContent> criteria) {
        return setCriteria(new LinkedHashMap<>(criteria));
      }

      abstract Optional<Map<String, @Nullable ClassifierContent>> criteria();

      /** Adds {@code label} with no description. */
      @CanIgnoreReturnValue
      public final Builder criterion(String label) {
        return criterion(label, (ClassifierContent) null);
      }

      /**
       * Adds {@code label} with {@code description}, which may be null. Adding an existing label
       * keeps its position and replaces its description.
       */
      @CanIgnoreReturnValue
      public final Builder criterion(String label, @Nullable ClassifierContent description) {
        Map<String, @Nullable ClassifierContent> criteria =
            new LinkedHashMap<>(criteria().orElse(ImmutableMap.of()));
        criteria.put(checkNotNull(label), description);
        return setCriteria(criteria);
      }

      /** Adds {@code label} with a plain-text {@code description}. */
      @CanIgnoreReturnValue
      public final Builder criterion(String label, String description) {
        return criterion(label, new ClassifierContent.Text(description));
      }

      abstract Builder setInstructions(@Nullable ClassifierContent instructions);

      abstract Builder setCriteria(Map<String, @Nullable ClassifierContent> criteria);

      abstract Choice autoBuild();

      /**
       * Builds the question.
       *
       * @throws IllegalStateException if the criteria are not set
       * @throws IllegalArgumentException if there are fewer than two labels or a label is null
       */
      public final Choice build() {
        checkState(criteria().isPresent(), "Choice.Builder requires criteria to be set.");
        return setCriteria(ClassifierValidation.choiceCriteria(criteria().get())).autoBuild();
      }
    }
  }

  /**
   * Asks a {@link Classifier} for the probability that a statement is true, answered by a {@link
   * Answer.BinaryAnswer}.
   */
  @AutoValue
  abstract non-sealed class Binary implements Question {

    Binary() {}

    /** Returns the yes-or-no question, if set. */
    @Override
    public abstract Optional<ClassifierContent> instructions();

    /** Returns a description of what counts as true, if set; anything else counts as false. */
    public abstract Optional<ClassifierContent> criteria();

    public static Builder builder() {
      return new AutoValue_Question_Binary.Builder();
    }

    public abstract Builder toBuilder();

    /** Builder for {@link Binary}. Every property is optional. */
    @AutoValue.Builder
    public abstract static class Builder {

      @CanIgnoreReturnValue
      public abstract Builder instructions(@Nullable ClassifierContent instructions);

      /**
       * Sets plain-text instructions; to clear them, pass null to {@link
       * #instructions(ClassifierContent)}.
       */
      @CanIgnoreReturnValue
      public final Builder instructions(String instructions) {
        return instructions(new ClassifierContent.Text(instructions));
      }

      @CanIgnoreReturnValue
      public abstract Builder criteria(@Nullable ClassifierContent criteria);

      /**
       * Sets plain-text criteria; to clear them, pass null to {@link #criteria(ClassifierContent)}.
       */
      @CanIgnoreReturnValue
      public final Builder criteria(String criteria) {
        return criteria(new ClassifierContent.Text(criteria));
      }

      public abstract Binary build();
    }
  }

  /**
   * Asks a {@link Classifier} to place an input on an ordered rubric starting at level 0, answered
   * by a {@link Answer.ScoreAnswer}.
   */
  @AutoValue
  abstract non-sealed class Score implements Question {

    Score() {}

    /** Returns what to rate, if set. */
    @Override
    public abstract Optional<ClassifierContent> instructions();

    /**
     * Returns one description per rubric level, starting at level 0. There are at least two levels.
     */
    public abstract ImmutableList<ClassifierContent> criteria();

    public static Builder builder() {
      return new AutoValue_Question_Score.Builder();
    }

    public abstract Builder toBuilder();

    /** Builder for {@link Score}. */
    @AutoValue.Builder
    public abstract static class Builder {

      @CanIgnoreReturnValue
      public abstract Builder instructions(@Nullable ClassifierContent instructions);

      /**
       * Sets plain-text instructions; to clear them, pass null to {@link
       * #instructions(ClassifierContent)}.
       */
      @CanIgnoreReturnValue
      public final Builder instructions(String instructions) {
        return instructions(new ClassifierContent.Text(instructions));
      }

      /** Sets the description of each rubric level, starting at level 0. */
      @CanIgnoreReturnValue
      public abstract Builder criteria(List<? extends ClassifierContent> criteria);

      /**
       * Sets plain-text descriptions, one per rubric level starting at 0; a score needs at least
       * two.
       */
      @CanIgnoreReturnValue
      public final Builder criteria(String first, String second, String... rest) {
        ImmutableList.Builder<ClassifierContent> levels = ImmutableList.builder();
        levels.add(new ClassifierContent.Text(first)).add(new ClassifierContent.Text(second));
        for (String level : rest) {
          levels.add(new ClassifierContent.Text(level));
        }
        return criteria(levels.build());
      }

      abstract Optional<ImmutableList<ClassifierContent>> criteria();

      abstract Score autoBuild();

      /**
       * Builds the question.
       *
       * @throws IllegalStateException if the criteria are not set
       * @throws IllegalArgumentException if there are fewer than two levels
       */
      public final Score build() {
        checkState(criteria().isPresent(), "Score.Builder requires criteria to be set.");
        checkArgument(
            criteria().get().size() >= 2,
            "Score requires at least 2 criteria levels, got %s.",
            criteria().get().size());
        return autoBuild();
      }
    }
  }
}
