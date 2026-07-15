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

import com.google.adk.skills.SkillSource;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;

/** Tool to list all available skills. */
final class ListSkillsTool extends BaseTool {
  private final SkillSource skillSource;

  ListSkillsTool(SkillSource skillSource) {
    super("list_skills", "Lists all available skills with their names and descriptions.");
    this.skillSource = skillSource;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parameters(
                Schema.builder().type(Type.Known.OBJECT).properties(ImmutableMap.of()).build())
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    return skillSource
        .listFrontmatters()
        .map(ImmutableMap::values)
        .map(SkillToolset::getSkillsPrompt)
        .<Map<String, Object>>map(skills -> ImmutableMap.of("skills_xml", skills))
        .onErrorResumeNext(SkillToolset::createErrorResponse);
  }
}
