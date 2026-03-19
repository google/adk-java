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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.ByteSource;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory implementation of {@link SkillSource}.
 *
 * <p>Everything is provided upfront using a builder pattern.
 */
public final class InMemorySkillSource implements SkillSource {

  private final ImmutableMap<String, SkillData> skills;

  private InMemorySkillSource(ImmutableMap<String, SkillData> skills) {
    this.skills = skills;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ImmutableMap<String, Frontmatter> listFrontmatters() {
    return ImmutableMap.copyOf(Maps.transformValues(skills, SkillData::frontmatter));
  }

  @Override
  public ImmutableList<String> listResources(String skillName, String resourceDirectory) {
    SkillData data = skills.get(skillName);
    if (data == null) {
      return ImmutableList.of();
    }
    String prefix = resourceDirectory.endsWith("/") ? resourceDirectory : resourceDirectory + "/";
    return data.resources().keySet().stream()
        .filter(path -> path.startsWith(prefix))
        .collect(toImmutableList());
  }

  private SkillData getSkillDataOrThrow(String skillName) {
    SkillData data = skills.get(skillName);
    if (data == null) {
      throw new SkillNotFoundException("Skill not found: " + skillName);
    }
    return data;
  }

  @Override
  public Frontmatter loadFrontmatter(String skillName) {
    return getSkillDataOrThrow(skillName).frontmatter();
  }

  @Override
  public String loadInstructions(String skillName) {
    return getSkillDataOrThrow(skillName).instructions();
  }

  @Override
  public ByteSource loadResource(String skillName, String resourcePath) {
    SkillData data = getSkillDataOrThrow(skillName);
    ByteSource source = data.resources().get(resourcePath);
    if (source == null) {
      throw new ResourceNotFoundException("Resource not found: " + resourcePath);
    }
    return source;
  }

  /** Builder for {@link InMemorySkillSource}. */
  public static class Builder {
    private final Map<String, SkillBuilder> skillBuilders = new HashMap<>();

    /** Returns a {@link SkillBuilder} for the specified skill, creating it if it doesn't exist. */
    public SkillBuilder skill(String name) {
      return skillBuilders.computeIfAbsent(name, k -> new SkillBuilder());
    }

    public InMemorySkillSource build() {
      return new InMemorySkillSource(
          ImmutableMap.copyOf(Maps.transformValues(skillBuilders, SkillBuilder::buildSkillData)));
    }

    /** Builder for a specific skill. */
    public final class SkillBuilder {
      private Frontmatter frontmatter;
      private String instructions;
      private final ImmutableMap.Builder<String, ByteSource> resourcesBuilder =
          ImmutableMap.builder();

      private SkillBuilder() {}

      @CanIgnoreReturnValue
      public SkillBuilder frontmatter(Frontmatter frontmatter) {
        this.frontmatter = frontmatter;
        return this;
      }

      @CanIgnoreReturnValue
      public SkillBuilder instructions(String instructions) {
        this.instructions = instructions;
        return this;
      }

      @CanIgnoreReturnValue
      public SkillBuilder addResource(String path, ByteSource content) {
        this.resourcesBuilder.put(path, content);
        return this;
      }

      @CanIgnoreReturnValue
      public SkillBuilder addResource(String path, byte[] content) {
        return addResource(path, ByteSource.wrap(content));
      }

      @CanIgnoreReturnValue
      public SkillBuilder addResource(String path, String content) {
        return addResource(path, content.getBytes(UTF_8));
      }

      /** Switches context to configure another skill, creating it if it doesn't exist. */
      public SkillBuilder skill(String name) {
        return Builder.this.skill(name);
      }

      /** Builds the {@link InMemorySkillSource} containing all configured skills. */
      public InMemorySkillSource build() {
        return Builder.this.build();
      }

      private SkillData buildSkillData() {
        checkState(frontmatter != null, "Frontmatter is required");
        checkState(instructions != null, "Instructions are required");
        return new SkillData(frontmatter, instructions, resourcesBuilder.buildOrThrow());
      }
    }
  }

  private record SkillData(
      Frontmatter frontmatter, String instructions, ImmutableMap<String, ByteSource> resources) {}
}
