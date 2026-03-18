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

/** Complete skill representation including frontmatter, instructions, and resources. */
@AutoValue
public abstract class Skill {

  public abstract Frontmatter frontmatter();

  public abstract String instructions();

  public abstract Resources resources();

  public String name() {
    return frontmatter().name();
  }

  public String description() {
    return frontmatter().description();
  }

  public static Builder builder() {
    return new AutoValue_Skill.Builder().resources(Resources.builder().build());
  }

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder frontmatter(Frontmatter frontmatter);

    public abstract Builder instructions(String instructions);

    public abstract Builder resources(Resources resources);

    public abstract Skill build();
  }
}
