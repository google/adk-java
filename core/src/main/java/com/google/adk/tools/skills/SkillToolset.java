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

import static java.util.Optional.ofNullable;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.SkillSource;
import com.google.adk.skills.SkillSourceException;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A toolset for managing and interacting with agent skills. Provides tools to list, load, and run
 * skills.
 */
public class SkillToolset implements BaseToolset {

  private static final String DEFAULT_SKILL_SYSTEM_INSTRUCTION =
      """
      You can use specialized 'skills' to help you with complex tasks. You MUST use the skill tools to interact with these skills.

      Skills are folders of instructions and resources that extend your capabilities for specialized tasks. Each skill folder contains:
      - **SKILL.md** (required): The main instruction file with skill metadata and detailed markdown instructions.
      - **references/** (Optional): Additional documentation or examples for skill usage.
      - **assets/** (Optional): Templates, scripts or other resources used by the skill.
      - **scripts/** (Optional): Executable scripts that can be run via bash.

      This is very important:

      1. If a skill seems relevant to the current user query, you MUST use the `load_skill` tool with `skill_name="<SKILL_NAME>"` to read its full instructions before proceeding.
      2. Once you have read the instructions, follow them exactly as documented before replying to the user. For example, If the instruction lists multiple steps, please make sure you complete all of them in order.
      3. The `load_skill_resource` tool is for viewing files within a skill's directory (e.g., `references/*`, `assets/*`, `scripts/*`). Do NOT use other tools to access these files.
      4. Use `run_skill_script` to run scripts from a skill's `scripts/` directory. Use `load_skill_resource` to view script content first if needed.
      """;

  private final SkillSource skillSource;
  private final ImmutableList<BaseTool> coreTools;
  private final String systemInstruction;

  /** Initializes the SkillToolset with a SkillSource and default execution settings. */
  public SkillToolset(SkillSource skillSource) {
    this(skillSource, DEFAULT_SKILL_SYSTEM_INSTRUCTION);
  }

  /** Initializes the SkillToolset with a SkillSource. */
  public SkillToolset(SkillSource skillSource, String systemInstruction) {
    this.skillSource = skillSource;
    this.systemInstruction = systemInstruction;
    this.coreTools =
        ImmutableList.of(
            new ListSkillsTool(skillSource),
            new LoadSkillTool(skillSource),
            new LoadSkillResourceTool(skillSource));
  }

  @Override
  public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
    return Flowable.fromIterable(coreTools);
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    return skillSource
        .listFrontmatters()
        .map(ImmutableMap::values)
        .map(SkillToolset::getSkillsPrompt)
        .map(
            skills ->
                llmRequestBuilder.appendInstructions(ImmutableList.of(systemInstruction, skills)))
        .ignoreElement();
  }

  @Override
  public void close() throws Exception {
    // No resources to release for now
  }

  static Single<Map<String, Object>> createErrorResponse(String errorMessage, String errorCode) {
    return Single.just(ImmutableMap.of("error", errorMessage, "error_code", errorCode));
  }

  static Single<Map<String, Object>> createErrorResponse(Throwable t) {
    if (t instanceof SkillSourceException ex) {
      return Single.just(
          ImmutableMap.of(
              "error",
              ofNullable(ex.getMessage()).orElse(ex.toString()),
              "error_code",
              ex.getErrorCode()));
    }
    return Single.error(t);
  }

  static String getSkillsPrompt(Collection<Frontmatter> frontmatters) {
    return frontmatters.stream()
        .map(Frontmatter::toXml)
        .reduce(
            new StringJoiner("\n", "<available_skills>", "</available_skills>").setEmptyValue(""),
            StringJoiner::add,
            StringJoiner::merge)
        .toString();
  }
}
