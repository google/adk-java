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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.skills.Script;
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
import java.util.Map;
import java.util.Optional;

/** Tool to load resources (references, assets, or scripts) from a skill. */
final class LoadSkillResourceTool extends BaseTool {

  private static final String BINARY_FILE_DETECTED_MSG =
      "Binary file detected. The content has been injected into the"
          + " conversation history for you to analyze.";
  private static final String SKILL_NAME = "skill_name";
  public static final String PATH = "path";

  private final SkillLoader skillLoader;

  LoadSkillResourceTool(SkillLoader skillLoader) {
    super(
        "load_skill_resource",
        "Loads a resource file (from references/, assets/, or scripts/) from within a skill.");
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
                                .description("The name of the skill.")
                                .build(),
                            PATH,
                            Schema.builder()
                                .type(Type.Known.STRING)
                                .description(
                                    "The relative path to the resource (e.g.,"
                                        + " 'references/my_doc.md', 'assets/template.txt',"
                                        + " or 'scripts/setup.sh').")
                                .build()))
                    .required(ImmutableList.of(SKILL_NAME, PATH))
                    .build())
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    String skillName = (String) args.get(SKILL_NAME);
    String resourcePath = (String) args.get(PATH);

    if (Strings.isNullOrEmpty(skillName)) {
      return createErrorResponse("Skill name is required.", "MISSING_SKILL_NAME");
    }
    if (Strings.isNullOrEmpty(resourcePath)) {
      return createErrorResponse("Resource path is required.", "MISSING_RESOURCE_PATH");
    }

    Skill skill;
    try {
      skill = skillLoader.loadSkill(skillName);
    } catch (Exception e) {
      return createErrorResponse("Skill '" + skillName + "' not found.", "SKILL_NOT_FOUND");
    }

    Object content;
    if (resourcePath.startsWith("references/")) {
      String refName = resourcePath.substring("references/".length());
      content = skill.resources().getReference(refName).orElse(null);
    } else if (resourcePath.startsWith("assets/")) {
      String assetName = resourcePath.substring("assets/".length());
      content = skill.resources().getAsset(assetName).orElse(null);
    } else if (resourcePath.startsWith("scripts/")) {
      String scriptName = resourcePath.substring("scripts/".length());
      content = skill.resources().getScript(scriptName).map(Script::src).orElse(null);
    } else {
      return createErrorResponse(
          "Path must start with 'references/', 'assets/', or 'scripts/'.", "INVALID_RESOURCE_PATH");
    }

    if (content == null) {
      return createErrorResponse(
          "Resource '" + resourcePath + "' not found in skill '" + skillName + "'.",
          "RESOURCE_NOT_FOUND");
    }

    return Single.just(
        ImmutableMap.of(SKILL_NAME, skillName, PATH, resourcePath, "content", getContent(content)));
  }

  /** Simple heuristic to check if a byte array is binary data. */
  private boolean isBinary(byte[] bytes) {
    for (int i = 0; i < Math.min(bytes.length, 1024); i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }

  private Object getContent(Object content) {
    if (content instanceof byte[] bytes) {
      return isBinary(bytes) ? BINARY_FILE_DETECTED_MSG : new String(bytes, UTF_8);
    }
    return content;
  }
}
