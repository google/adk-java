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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.Resources;
import com.google.adk.skills.Script;
import com.google.adk.skills.Skill;
import com.google.adk.skills.SkillLoader;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LoadSkillResourceToolTest {

  @Test
  public void call_loadSkillResourceTool_reference_success() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "path", "references/my_doc.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "path", "references/my_doc.md",
            "content", "doc content");
  }

  @Test
  public void call_loadSkillResourceTool_asset_success() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "path", "assets/template.txt"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "path", "assets/template.txt",
            "content", "asset content");
  }

  @Test
  public void call_loadSkillResourceTool_script_success() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "path", "scripts/setup.sh"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "path", "scripts/setup.sh",
            "content", "echo hello");
  }

  @Test
  public void call_loadSkillResourceTool_binaryReference_detected() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "path", "references/binary.dat"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "path", "references/binary.dat",
            "content",
                "Binary file detected. The content has been injected into the conversation history"
                    + " for you to analyze.");
  }

  @Test
  public void call_loadSkillResourceTool_missingSkillName() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(ImmutableMap.of("path", "references/my_doc.md"), createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Skill name is required.",
            "error_code", "MISSING_SKILL_NAME");
  }

  @Test
  public void call_loadSkillResourceTool_missingPath() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(ImmutableMap.of("skill_name", "test-skill"), createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Resource path is required.",
            "error_code", "MISSING_RESOURCE_PATH");
  }

  @Test
  public void call_loadSkillResourceTool_skillNotFound() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "other-skill", "path", "references/my_doc.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Skill 'other-skill' not found.",
            "error_code", "SKILL_NOT_FOUND");
  }

  @Test
  public void call_loadSkillResourceTool_invalidPathPrefix() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "path", "invalid/my_doc.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Path must start with 'references/', 'assets/', or 'scripts/'.",
            "error_code", "INVALID_RESOURCE_PATH");
  }

  @Test
  public void call_loadSkillResourceTool_resourceNotFound() {
    Skill testSkill = createTestSkill();
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "path", "references/missing.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Resource 'references/missing.md' not found in skill 'test-skill'.",
            "error_code", "RESOURCE_NOT_FOUND");
  }

  private ToolContext createToolContext() {
    TestBaseAgent testAgent =
        new TestBaseAgent(
            "test agent", "test agent", ImmutableList.of(), ImmutableList.of(), Flowable::empty);
    Session session = Session.builder("session").build();

    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.agent()).thenReturn(testAgent);
    when(invocationContext.session()).thenReturn(session);

    return ToolContext.builder(invocationContext).build();
  }

  private Skill createTestSkill() {
    return Skill.builder()
        .frontmatter(Frontmatter.builder().name("test-skill").description("test skill").build())
        .instructions("Test instructions")
        .resources(
            Resources.builder()
                .references(
                    ImmutableMap.of(
                        "my_doc.md",
                        "doc content".getBytes(UTF_8),
                        "binary.dat",
                        new byte[] {0, 1, 2, 3}))
                .assets(ImmutableMap.of("template.txt", "asset content".getBytes(UTF_8)))
                .scripts(ImmutableMap.of("setup.sh", Script.create("echo hello")))
                .build())
        .build();
  }
}
