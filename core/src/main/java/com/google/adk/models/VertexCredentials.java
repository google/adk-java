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
import java.util.Optional;
import javax.annotation.Nullable;

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

    public abstract Builder setProject(@Nullable String value);

    public abstract Builder setLocation(@Nullable String value);

    public abstract Builder setCredentials(@Nullable GoogleCredentials value);

    public abstract VertexCredentials build();
  }
}
