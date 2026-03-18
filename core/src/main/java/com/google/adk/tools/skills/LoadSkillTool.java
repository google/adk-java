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
import static java.util.Arrays.stream;

import com.google.adk.skills.Skill;
import com.google.adk.skills.SkillLoader;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Tool to load a skill's instructions. */
final class LoadSkillTool extends BaseTool {

  private static final String SKILL_NAME = "skill_name";
  private final SkillLoader skillLoader;

  LoadSkillTool(SkillLoader skillLoader) {
    super("load_skill", "Loads the SKILL.md instructions for a given skill.");
    this.skillLoader = skillLoader;
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

    Skill skill;
    try {
      skill = skillLoader.loadSkill(skillName);
    } catch (Exception e) {
      return createErrorResponse("Skill '" + skillName + "' not found.", "SKILL_NOT_FOUND");
    }

    // Record skill activation in agent state for tool resolution.
    String agentName = toolContext.invocationContext().agent().name();
    String stateKey = "_adk_activated_skill_" + agentName;

    Set<String> activatedSkills = new LinkedHashSet<>();
    Object existingState = toolContext.invocationContext().session().state().get(stateKey);
    if (existingState instanceof List<?> list) {
      list.stream().map(Object::toString).forEach(activatedSkills::add);
    } else if (existingState instanceof Object[] objs) {
      stream(objs)
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .forEach(activatedSkills::add);
    }
    activatedSkills.add(skillName);
    toolContext
        .invocationContext()
        .session()
        .state()
        .put(stateKey, ImmutableList.copyOf(activatedSkills));

    return Single.just(
        ImmutableMap.of(
            "skill_name", skillName,
            "instructions", skill.instructions(),
            "frontmatter", skill.frontmatter()));
  }
}
