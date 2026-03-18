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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RunSkillScriptToolTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private BaseCodeExecutor mockCodeExecutor;
  private ToolContext toolContext;
  private RunSkillScriptTool runSkillScriptTool;

  @Before
  public void setUp() {
    Skill testSkill = createTestSkill();
    runSkillScriptTool =
        new RunSkillScriptTool(SkillLoader.fromSkills(testSkill), mockCodeExecutor, 30);
    toolContext = createToolContext();
  }

  @Test
  public void call_runSkillScriptTool_pythonScript_success() {
    when(mockCodeExecutor.executeCode(any(), any()))
        .thenReturn(CodeExecutionResult.builder().stdout("hello world").stderr("").build());

    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(
                ImmutableMap.of(
                    "skill_name", "test-skill",
                    "script_path", "scripts/main.py",
                    "args", ImmutableMap.of("opt1", "val1")),
                toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "script_path", "scripts/main.py",
            "stdout", "hello world",
            "stderr", "",
            "status", "success");
  }

  @Test
  public void call_runSkillScriptTool_bashScript_success() throws Exception {
    String mockShellJson =
        OBJECT_MAPPER.writeValueAsString(
            ImmutableMap.of(
                "__shell_result__", true, "stdout", "shell output", "stderr", "", "returncode", 0));
    when(mockCodeExecutor.executeCode(any(), any()))
        .thenReturn(CodeExecutionResult.builder().stdout(mockShellJson).stderr("").build());

    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "script_path", "scripts/setup.sh"),
                toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "script_path", "scripts/setup.sh",
            "stdout", "shell output",
            "stderr", "",
            "status", "success");
  }

  @Test
  public void call_runSkillScriptTool_missingSkillName() {
    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(ImmutableMap.of("script_path", "scripts/setup.sh"), toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Skill name is required.",
            "error_code", "MISSING_SKILL_NAME");
  }

  @Test
  public void call_runSkillScriptTool_missingScriptPath() {
    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(ImmutableMap.of("skill_name", "test-skill"), toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Script path is required.",
            "error_code", "MISSING_SCRIPT_PATH");
  }

  @Test
  public void call_runSkillScriptTool_skillNotFound() {
    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(
                ImmutableMap.of("skill_name", "other-skill", "script_path", "scripts/setup.sh"),
                toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Skill 'other-skill' not found.",
            "error_code", "SKILL_NOT_FOUND");
  }

  @Test
  public void call_runSkillScriptTool_scriptNotFound() {
    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "script_path", "scripts/missing.py"),
                toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Script 'scripts/missing.py' not found in skill 'test-skill'.",
            "error_code", "SCRIPT_NOT_FOUND");
  }

  @Test
  public void call_runSkillScriptTool_unsupportedScriptType() {
    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "script_path", "scripts/unknown.xxx"),
                toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Unsupported script type. Supported types: .py, .sh, .bash",
            "error_code", "UNSUPPORTED_SCRIPT_TYPE");
  }

  @Test
  public void call_runSkillScriptTool_executionError() {
    when(mockCodeExecutor.executeCode(any(), any()))
        .thenThrow(new RuntimeException("executor crashed"));

    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "script_path", "scripts/main.py"),
                toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error",
                "Failed to execute script 'scripts/main.py':\nRuntimeException: executor crashed",
            "error_code", "EXECUTION_ERROR");
  }

  @Test
  public void call_runSkillScriptTool_shellExecutionError() throws Exception {
    String mockShellJson =
        OBJECT_MAPPER.writeValueAsString(
            ImmutableMap.of(
                "__shell_result__", true, "stdout", "", "stderr", "exit error", "returncode", 1));
    when(mockCodeExecutor.executeCode(any(), any()))
        .thenReturn(CodeExecutionResult.builder().stdout(mockShellJson).stderr("").build());

    Map<String, Object> response =
        runSkillScriptTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "script_path", "scripts/setup.sh"),
                toolContext)
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "script_path", "scripts/setup.sh",
            "stdout", "",
            "stderr", "exit error",
            "status", "error");
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
                .scripts(
                    ImmutableMap.of(
                        "main.py", Script.create("print('py')"),
                        "setup.sh", Script.create("echo sh"),
                        "unknown.xxx", Script.create("???")))
                .build())
        .build();
  }
}
