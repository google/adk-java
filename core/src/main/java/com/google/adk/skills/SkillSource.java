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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Single;

/**
 * Interface for getting access to available skills.
 *
 * <p>All operations are asynchronous and communicate failures reactively through the returned
 * {@link Single} error channel (terminating with {@code onError}), rather than throwing exceptions
 * synchronously. Implementation must use the {@link SkillSourceException} for propagating error
 * message back to the LLM.
 */
public interface SkillSource {

  /**
   * Lists all available {@link Frontmatter}s for discovered skills.
   *
   * <p>If the source is misconfigured, such as directory doesn't exist, or having malformed skill,
   * the returned {@link Single} will terminate with a {@link SkillSourceException} with the reason
   * in the message.
   *
   * @return a {@link Single} emitting a map where keys are skill names and values are their {@link
   *     Frontmatter}
   */
  Single<ImmutableMap<String, Frontmatter>> listFrontmatters();

  /**
   * Lists all resource files for a specific skill within a given directory.
   *
   * <p>If the skill or the resource directory does not exist, the returned {@link Single} will
   * terminate with a {@link SkillSourceException}.
   *
   * @param skillName the name of the skill
   * @param resourceDirectory the relative directory within the skill to list (e.g., "assets",
   *     "scripts")
   * @return a {@link Single} emitting a list of resource paths relative to the skill directory
   */
  Single<ImmutableList<String>> listResources(String skillName, String resourceDirectory);

  /**
   * Loads the {@link Frontmatter} for a specific skill.
   *
   * <p>If the skill is not found or its frontmatter is malformed, the returned {@link Single} will
   * terminate with a {@link SkillSourceException} or parsing error.
   *
   * @param skillName the name of the skill
   * @return a {@link Single} emitting the {@link Frontmatter} for the skill
   */
  Single<Frontmatter> loadFrontmatter(String skillName);

  /**
   * Loads the instructions (body of SKILL.md) for a specific skill.
   *
   * <p>If the skill is not found or its file structure is invalid (e.g., unclosed frontmatter
   * blocks), the returned {@link Single} will terminate with a {@link SkillSourceException}.
   *
   * @param skillName the name of the skill
   * @return a {@link Single} emitting the instructions as a String
   */
  Single<String> loadInstructions(String skillName);

  /**
   * Loads a specific resource file content.
   *
   * <p>If the skill or the specific resource path cannot be found, the returned {@link Single} will
   * terminate with a {@link SkillSourceException}.
   *
   * @param skillName the name of the skill
   * @param resourcePath the path to the resource file relative to the skill directory
   * @return a {@link Single} emitting the {@link ByteSource} for the resource content
   */
  Single<ByteSource> loadResource(String skillName, String resourcePath);
}
