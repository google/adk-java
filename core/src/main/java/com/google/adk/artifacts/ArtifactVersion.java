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

package com.google.adk.artifacts;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Metadata describing a specific version of an artifact.
 *
 * <p>This describes a stored payload without being one, so a caller can learn where a version
 * lives, what type it is and what was attached to it without downloading it.
 */
@AutoValue
public abstract class ArtifactVersion {

  ArtifactVersion() {}

  /** Monotonically increasing identifier for the artifact version. */
  public abstract int version();

  /** Canonical URI referencing the persisted artifact payload. */
  public abstract String canonicalUri();

  /** Metadata the caller attached to the artifact, empty when none was attached. */
  public abstract ImmutableMap<String, Object> customMetadata();

  /** When the version record was created. */
  public abstract Instant createTime();

  /** MIME type of the payload, empty when the store does not know it. */
  public abstract Optional<String> mimeType();

  public static Builder builder() {
    return new AutoValue_ArtifactVersion.Builder().customMetadata(ImmutableMap.of());
  }

  /** Builder for {@link ArtifactVersion}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder version(int version);

    public abstract Builder canonicalUri(String canonicalUri);

    public abstract Builder customMetadata(Map<String, Object> customMetadata);

    public abstract Builder createTime(Instant createTime);

    public abstract Builder mimeType(@Nullable String mimeType);

    public abstract ArtifactVersion build();
  }
}
