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

import static com.google.adk.tools.skills.ListSkillsTool.getSkillsPrompt;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.models.LlmRequest;
import com.google.adk.skills.Skill;
import com.google.adk.skills.SkillLoader;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A toolset for managing and interacting with agent skills. Provides tools to list, load, and run
 * skills.
 */
public class SkillToolset implements BaseToolset {

  private static final long DEFAULT_SCRIPT_TIMEOUT = 300L;
  private static final String DEFAULT_SKILL_SYSTEM_INSTRUCTION =
      """
      You can use specialized 'skills' to help you with complex tasks. You MUST use the skill tools to interact with these skills.

      Skills are folders of instructions and resources that extend your capabilities for specialized tasks. Each skill folder contains:
      - **SKILL.md** (required): The main instruction file with skill metadata and detailed markdown instructions.
      - **references/** (Optional): Additional documentation or examples for skill usage.
      - **assets/** (Optional): Templates, scripts or other resources used by the skill.
      - **scripts/** (Optional): Executable scripts that can be run via bash.

      This is very important:

      1. If a skill seems relevant to the current user query, you MUST use the `load_skill` tool with `name="<SKILL_NAME>"` to read its full instructions before proceeding.
      2. Once you have read the instructions, follow them exactly as documented before replying to the user. For example, If the instruction lists multiple steps, please make sure you complete all of them in order.
      3. The `load_skill_resource` tool is for viewing files within a skill's directory (e.g., `references/*`, `assets/*`, `scripts/*`). Do NOT use other tools to access these files.
      4. Use `run_skill_script` to run scripts from a skill's `scripts/` directory. Use `load_skill_resource` to view script content first if needed.
      """;

  private static final Logger logger = LoggerFactory.getLogger(SkillToolset.class);

  private final SkillLoader skillLoader;
  private final ImmutableList<BaseTool> coreTools;
  private final ImmutableMap<String, BaseTool> providedTools;

  public SkillToolset(SkillLoader skillLoader) {
    this(skillLoader, null);
  }

  /** Initializes the SkillToolset with a SkillLoader and default timeout. */
  public SkillToolset(SkillLoader skillLoader, @Nullable BaseCodeExecutor codeExecutor) {
    this(skillLoader, codeExecutor, DEFAULT_SCRIPT_TIMEOUT, ImmutableMap.of());
  }

  /** Initializes the SkillToolset with a SkillLoader. */
  public SkillToolset(
      SkillLoader skillLoader,
      @Nullable BaseCodeExecutor codeExecutor,
      long scriptTimeoutSeconds,
      Map<String, BaseTool> providedTools) {
    this.skillLoader = skillLoader;
    this.providedTools = ImmutableMap.copyOf(providedTools);
    this.coreTools =
        ImmutableList.of(
            new ListSkillsTool(skillLoader),
            new LoadSkillTool(skillLoader),
            new LoadSkillResourceTool(skillLoader),
            new RunSkillScriptTool(skillLoader, codeExecutor, scriptTimeoutSeconds));
  }

  @Override
  public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
    List<BaseTool> dynamicTools = resolveAdditionalToolsFromState(readonlyContext);
    return Flowable.fromIterable(
        ImmutableList.<BaseTool>builder().addAll(coreTools).addAll(dynamicTools).build());
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    llmRequestBuilder.appendInstructions(
        ImmutableList.of(
            DEFAULT_SKILL_SYSTEM_INSTRUCTION, getSkillsPrompt(skillLoader.listSkills().values())));
    return Completable.complete();
  }

  @Override
  public void close() throws Exception {
    // No resources to release for now
  }

  static Single<Map<String, Object>> createErrorResponse(String errorMessage, String errorCode) {
    return Single.just(ImmutableMap.of("error", errorMessage, "error_code", errorCode));
  }

  private List<BaseTool> resolveAdditionalToolsFromState(ReadonlyContext readonlyContext) {
    if (providedTools.isEmpty()) {
      return ImmutableList.of();
    }

    String agentName = readonlyContext.agentName();
    String stateKey = "_adk_activated_skill_" + agentName;

    Object stateVal = readonlyContext.state().get(stateKey);
    if (!(stateVal instanceof List<?> activatedSkills)) {
      return ImmutableList.of();
    }

    List<BaseTool> resolvedTools = new ArrayList<>();

    for (Object skillNameObj : activatedSkills) {
      if (!(skillNameObj instanceof String skillName)) {
        continue;
      }

      Skill skill;
      try {
        skill = skillLoader.loadSkill(skillName);
      } catch (Exception e) {
        logger.warn("Skill not exists, ignored: {}", skillName);
        continue;
      }
      if (skill.frontmatter().metadata() == null) {
        continue;
      }
      Object additionalToolsObj = skill.frontmatter().metadata().get("adk_additional_tools");
      if (additionalToolsObj instanceof List<?> additionalTools) {
        additionalTools.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(providedTools::get)
            .filter(Objects::nonNull)
            .forEach(resolvedTools::add);
      }
    }

    return ImmutableList.copyOf(resolvedTools);
  }
}
