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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Asks a {@link BaseClassifier} to pick one of two or more labels, answered by a {@link
 * ChoiceAnswer}.
 */
@Experimental
@AutoValue
@JsonDeserialize(using = ClassifierJson.ChoiceDeserializer.class)
public abstract non-sealed class Choice implements Question {

  Choice() {}

  @Override
  public abstract Optional<Object> instructions();

  /**
   * Returns each label, in the order it was added, mapped to a text or JSON-content description of
   * when it applies, or to null for none. There are at least two labels.
   */
  // Descriptions may be null, which ImmutableMap rejects; the map is unmodifiable.
  @SuppressWarnings("AutoValueImmutableFields")
  public abstract Map<String, @Nullable Object> criteria();

  public static Builder builder() {
    return new AutoValue_Choice.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link Choice}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public final Builder instructions(@Nullable Object instructions) {
      return setInstructions(instructions);
    }

    abstract Optional<Object> instructions();

    /** Sets every label and its description, replacing any labels set before. */
    @CanIgnoreReturnValue
    public final Builder criteria(Map<String, ?> criteria) {
      return setCriteria(new LinkedHashMap<>(criteria));
    }

    abstract Optional<Map<String, @Nullable Object>> criteria();

    /** Adds {@code label} with no description. */
    @CanIgnoreReturnValue
    public final Builder criterion(String label) {
      return criterion(label, /* description= */ null);
    }

    /**
     * Adds {@code label} with {@code description}, which may be null. Adding an existing label
     * keeps its position and replaces its description.
     */
    @CanIgnoreReturnValue
    public final Builder criterion(String label, @Nullable Object description) {
      Map<String, @Nullable Object> criteria =
          new LinkedHashMap<>(criteria().orElse(ImmutableMap.of()));
      criteria.put(checkNotNull(label), description);
      return setCriteria(criteria);
    }

    abstract Builder setInstructions(@Nullable Object instructions);

    abstract Builder setCriteria(Map<String, @Nullable Object> criteria);

    abstract Choice autoBuild();

    /**
     * Builds the question.
     *
     * @throws IllegalStateException if the criteria are not set
     * @throws IllegalArgumentException if there are fewer than two labels, or if the instructions
     *     or a description is not JSON content
     */
    public final Choice build() {
      checkState(criteria().isPresent(), "Choice.Builder requires criteria to be set.");
      return setCriteria(ClassifierValidation.choiceCriteria(criteria().get()))
          .setInstructions(ClassifierValidation.optionalJsonContent(instructions().orElse(null)))
          .autoBuild();
    }
  }
}
