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

package com.google.adk.tools.skills;

import static com.google.adk.tools.skills.SkillToolset.createErrorResponse;

import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;

/** Tool to load a skill's instructions. */
final class LoadSkillTool extends BaseTool {

  private static final String SKILL_NAME = "skill_name";
  private final SkillSource skillSource;

  LoadSkillTool(SkillSource skillSource) {
    super("load_skill", "Loads the SKILL.md instructions for a given skill.");
    this.skillSource = skillSource;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parameters(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(
                        ImmutableMap.of(
                            SKILL_NAME,
                            Schema.builder()
                                .type(Type.Known.STRING)
                                .description("The name of the skill to load.")
                                .build()))
                    .required(ImmutableList.of(SKILL_NAME))
                    .build())
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    String skillName = (String) args.get(SKILL_NAME);
    if (Strings.isNullOrEmpty(skillName)) {
      return createErrorResponse("Skill name is required.", "MISSING_SKILL_NAME");
    }

    return skillSource
        .loadFrontmatter(skillName)
        .<String, Map<String, Object>>zipWith(
            skillSource.loadInstructions(skillName),
            (frontmatter, instructions) ->
                ImmutableMap.of(
                    "skill_name",
                    skillName,
                    "frontmatter",
                    frontmatterToMap(frontmatter),
                    "instructions",
                    instructions))
        .onErrorResumeNext(SkillToolset::createErrorResponse);
  }

  private static ImmutableMap<String, Object> frontmatterToMap(Frontmatter fm) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.put("name", fm.name()).put("description", fm.description());
    fm.license().ifPresent(l -> builder.put("license", l));
    fm.compatibility().ifPresent(c -> builder.put("compatibility", c));
    fm.allowedTools().ifPresent(a -> builder.put("allowed-tools", a));
    return builder.put("metadata", fm.metadata()).buildOrThrow();
  }
}
