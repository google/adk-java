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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.adk.skills.Frontmatter;
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
public class LoadSkillToolTest {

  @Test
  public void call_loadSkillTool_success() {
    Skill testSkill =
        Skill.builder()
            .frontmatter(Frontmatter.builder().name("test-skill").description("test skill").build())
            .instructions("Test instructions")
            .build();

    TestBaseAgent testAgent =
        new TestBaseAgent(
            "test agent", "test agent", ImmutableList.of(), ImmutableList.of(), Flowable::empty);
    Session session = Session.builder("session").build();

    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.agent()).thenReturn(testAgent);
    when(invocationContext.session()).thenReturn(session);

    LoadSkillTool loadSkillTool = new LoadSkillTool(SkillLoader.fromSkills(testSkill));
    Map<String, Object> response =
        loadSkillTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill"),
                ToolContext.builder(invocationContext).build())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name",
            "test-skill",
            "instructions",
            "Test instructions",
            "frontmatter",
            Frontmatter.builder().name("test-skill").description("test skill").build());
  }
}
