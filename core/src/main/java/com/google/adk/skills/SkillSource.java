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

/** Interface for getting access to available skills. */
public interface SkillSource {

  /**
   * Lists all available {@link Frontmatter}s for skills.
   *
   * @return a map where keys are skill names and values are their {@link Frontmatter}
   */
  ImmutableMap<String, Frontmatter> listFrontmatters();

  /**
   * Lists all resource files for a specific skill within a given directory.
   *
   * @param skillName the name of the skill
   * @param resourceDirectory the relative directory within the skill to list (e.g., "assets",
   *     "scripts")
   * @return a list of resource paths relative to the skill directory
   */
  ImmutableList<String> listResources(String skillName, String resourceDirectory);

  /**
   * Loads the {@link Frontmatter} for a specific skill.
   *
   * @param skillName the name of the skill
   * @return the {@link Frontmatter} for the skill
   * @throws SkillNotFoundException if the skill is not found
   */
  Frontmatter loadFrontmatter(String skillName);

  /**
   * Loads the instructions (body of SKILL.md) for a specific skill.
   *
   * @param skillName the name of the skill
   * @return the instructions as a String
   * @throws SkillNotFoundException if the skill is not found
   */
  String loadInstructions(String skillName);

  /**
   * Loads a specific resource file content.
   *
   * @param skillName the name of the skill
   * @param resourcePath the path to the resource file relative to the skill directory
   * @return the {@link ByteSource} for the resource
   * @throws SkillNotFoundException if the skill is not found
   * @throws ResourceNotFoundException if the resource is not found
   */
  ByteSource loadResource(String skillName, String resourcePath);
}
