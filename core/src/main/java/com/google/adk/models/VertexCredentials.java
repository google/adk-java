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

package com.google.adk.models;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Credentials for accessing Gemini models through Vertex. */
@AutoValue
public abstract class VertexCredentials {

  public abstract Optional<String> project();

  public abstract Optional<String> location();

  public abstract Optional<GoogleCredentials> credentials();

  public static Builder builder() {
    return new AutoValue_VertexCredentials.Builder();
  }

  /** Builder for {@link VertexCredentials}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setProject(@Nullable String value) {
      return project(value);
    }

    @CanIgnoreReturnValue
    public abstract Builder project(@Nullable String value);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setLocation(@Nullable String value) {
      return location(value);
    }

    @CanIgnoreReturnValue
    public abstract Builder location(@Nullable String value);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setCredentials(@Nullable GoogleCredentials value) {
      return credentials(value);
    }

    @CanIgnoreReturnValue
    public abstract Builder credentials(@Nullable GoogleCredentials value);

    public abstract VertexCredentials build();
  }
}
