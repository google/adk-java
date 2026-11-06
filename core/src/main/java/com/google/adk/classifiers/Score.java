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

import static com.google.common.base.Preconditions.checkState;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Asks a {@link BaseClassifier} to place an input on an ordered rubric starting at level 0,
 * answered by a {@link ScoreAnswer}.
 */
@Experimental
@AutoValue
@JsonDeserialize(using = ClassifierJson.ScoreDeserializer.class)
public abstract non-sealed class Score implements Question {

  Score() {}

  /** Returns what to rate, as text or JSON content, if set. */
  @Override
  public abstract Optional<Object> instructions();

  /**
   * Returns one text or JSON-content description per rubric level, starting at level 0. There are
   * at least two levels.
   */
  public abstract ImmutableList<Object> criteria();

  public static Builder builder() {
    return new AutoValue_Score.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link Score}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder instructions(@Nullable Object instructions);

    abstract Optional<Object> instructions();

    /** Sets the description of each rubric level, starting at level 0. */
    @CanIgnoreReturnValue
    public abstract Builder criteria(List<?> criteria);

    abstract Optional<ImmutableList<Object>> criteria();

    abstract Score autoBuild();

    /**
     * Builds the question.
     *
     * @throws IllegalStateException if the criteria are not set
     * @throws IllegalArgumentException if there are fewer than two levels, or if the instructions
     *     or a level is not JSON content
     */
    public final Score build() {
      checkState(criteria().isPresent(), "Score.Builder requires criteria to be set.");
      return criteria(ClassifierValidation.scoreCriteria(criteria().get()))
          .instructions(ClassifierValidation.optionalJsonContent(instructions().orElse(null)))
          .autoBuild();
    }
  }
}
