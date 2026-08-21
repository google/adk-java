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

package com.google.adk.artifacts;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import java.util.List;

/** Response for listing artifact versions. */
@AutoValue
public abstract class ListArtifactVersionsResponse {

  public abstract ImmutableList<Part> versions();

  /** Builder for {@link ListArtifactVersionsResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder versions(List<Part> versions);

    public abstract ListArtifactVersionsResponse build();
  }

  public static Builder builder() {
    return new AutoValue_ListArtifactVersionsResponse.Builder();
  }
}
