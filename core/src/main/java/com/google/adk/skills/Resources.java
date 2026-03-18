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

package com.google.adk.skills;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** L3 skill content: additional instructions, assets, and scripts. */
@AutoValue
public abstract class Resources {

  public abstract ImmutableMap<String, byte[]> references();

  public abstract ImmutableMap<String, byte[]> assets();

  public abstract ImmutableMap<String, Script> scripts();

  public Optional<byte[]> getReference(String referenceId) {
    return Optional.ofNullable(references().get(referenceId));
  }

  public Optional<byte[]> getAsset(String assetId) {
    return Optional.ofNullable(assets().get(assetId));
  }

  public Optional<Script> getScript(String scriptId) {
    return Optional.ofNullable(scripts().get(scriptId));
  }

  public static Builder builder() {
    return new AutoValue_Resources.Builder()
        .references(ImmutableMap.of())
        .assets(ImmutableMap.of())
        .scripts(ImmutableMap.of());
  }

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder references(ImmutableMap<String, byte[]> references);

    public abstract Builder assets(ImmutableMap<String, byte[]> assets);

    public abstract Builder scripts(ImmutableMap<String, Script> scripts);

    public abstract Resources build();
  }
}
