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

package com.google.adk.agents;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Stores the data that can be used for transcription.
 *
 * <p>A live audio invocation caches one of these per thing that was said, so that the audio can be
 * bundled by speaker and transcribed later.
 *
 * <p>An entry carries either a {@link Blob} of audio that still needs recognising or a {@link
 * Content} of text the model already produced, never both and never neither. The two are separate
 * fields rather than one field of an open type, the same way {@link LiveRequest} carries the same
 * pair.
 */
@AutoValue
public abstract class TranscriptionEntry {

  TranscriptionEntry() {}

  /**
   * Returns the role that created this data, typically {@code "user"} or {@code "model"}.
   *
   * <p>Empty for a function call, which nobody spoke.
   */
  public abstract Optional<String> role();

  /** Returns audio that still needs recognising, empty when the entry carries text instead. */
  public abstract Optional<Blob> blob();

  /** Returns text the model already produced, empty when the entry carries audio instead. */
  public abstract Optional<Content> content();

  /** Returns a new builder for creating a {@link TranscriptionEntry}. */
  public static Builder builder() {
    return new AutoValue_TranscriptionEntry.Builder();
  }

  /** Returns a new builder with a copy of this entry's values. */
  public abstract Builder toBuilder();

  /** Builder for {@link TranscriptionEntry}. */
  @AutoValue.Builder
  public abstract static class Builder {

    /** Sets the role that created this data. */
    public abstract Builder role(@Nullable String role);

    /** Sets audio that still needs recognising as the data of this entry. */
    public abstract Builder blob(@Nullable Blob blob);

    /** Sets text the model already produced as the data of this entry. */
    public abstract Builder content(@Nullable Content content);

    abstract TranscriptionEntry autoBuild();

    /** Builds the entry, refusing one that carries neither kind of data or both. */
    public final TranscriptionEntry build() {
      TranscriptionEntry entry = autoBuild();
      Preconditions.checkState(
          entry.blob().isPresent() != entry.content().isPresent(),
          "Exactly one of blob or content must be set");
      return entry;
    }
  }
}
