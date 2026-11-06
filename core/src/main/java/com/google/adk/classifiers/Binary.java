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

/**
 * Asks a {@link BaseClassifier} for the probability that a statement is true, answered by a {@link
 * BinaryAnswer}.
 */
@Experimental
@AutoValue
@JsonDeserialize(using = ClassifierJson.BinaryDeserializer.class)
public abstract non-sealed class Binary implements Question {

  Binary() {}

  /** Returns the yes-or-no question, as text or JSON content, if set. */
  @Override
  public abstract Optional<Object> instructions();

  /**
   * Returns a text or JSON-content description of what counts as true, if set; anything else counts
   * as false.
   */
  public abstract Optional<Object> criteria();

  public static Builder builder() {
    return new AutoValue_Binary.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link Binary}. Every property is optional. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder instructions(@Nullable Object instructions);

    abstract Optional<Object> instructions();

    @CanIgnoreReturnValue
    public abstract Builder criteria(@Nullable Object criteria);

    abstract Optional<Object> criteria();

    abstract Binary autoBuild();

    /**
     * Builds the question.
     *
     * @throws IllegalArgumentException if the instructions or criteria are not JSON content
     */
    public final Binary build() {
      return instructions(ClassifierValidation.optionalJsonContent(instructions().orElse(null)))
          .criteria(ClassifierValidation.optionalJsonContent(criteria().orElse(null)))
          .autoBuild();
    }
  }
}
