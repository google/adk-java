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

import static com.google.adk.skills.SkillSourceException.SKILL_LOAD_ERROR;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.InMemorySkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.skills.SkillSourceException;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ListSkillsToolTest {

  @Test
  public void call_listSkillsTool_success() {
    Frontmatter testFrontmatter =
        Frontmatter.builder().name("test-skill").description("test skill").build();

    TestBaseAgent testAgent =
        new TestBaseAgent(
            "test agent", "test agent", ImmutableList.of(), ImmutableList.of(), Flowable::empty);
    Session session = Session.builder("session").build();

    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.agent()).thenReturn(testAgent);
    when(invocationContext.session()).thenReturn(session);

    SkillSource skillSource =
        InMemorySkillSource.builder()
            .skill(testFrontmatter.name())
            .frontmatter(testFrontmatter)
            .instructions("Test instructions")
            .build();
    ListSkillsTool listSkillsTool = new ListSkillsTool(skillSource);
    Map<String, Object> response =
        listSkillsTool
            .runAsync(ImmutableMap.of(), ToolContext.builder(invocationContext).build())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skills_xml", "<available_skills>" + testFrontmatter.toXml() + "</available_skills>");
  }

  @Test
  public void call_listSkillsTool_empty() {
    TestBaseAgent testAgent =
        new TestBaseAgent(
            "test agent", "test agent", ImmutableList.of(), ImmutableList.of(), Flowable::empty);
    Session session = Session.builder("session").build();

    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.agent()).thenReturn(testAgent);
    when(invocationContext.session()).thenReturn(session);

    ListSkillsTool listSkillsTool = new ListSkillsTool(InMemorySkillSource.builder().build());
    Map<String, Object> response =
        listSkillsTool
            .runAsync(ImmutableMap.of(), ToolContext.builder(invocationContext).build())
            .blockingGet();

    assertThat(response).containsExactly("skills_xml", "");
  }

  @Test
  public void call_listSkillsTool_skillSourceException() {
    TestBaseAgent testAgent =
        new TestBaseAgent(
            "test agent", "test agent", ImmutableList.of(), ImmutableList.of(), Flowable::empty);
    Session session = Session.builder("session").build();

    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.agent()).thenReturn(testAgent);
    when(invocationContext.session()).thenReturn(session);

    SkillSource skillSource = mock(SkillSource.class);
    when(skillSource.listFrontmatters())
        .thenReturn(
            Single.error(new SkillSourceException("Failed to list skills", SKILL_LOAD_ERROR)));

    ListSkillsTool listSkillsTool = new ListSkillsTool(skillSource);
    Map<String, Object> response =
        listSkillsTool
            .runAsync(ImmutableMap.of(), ToolContext.builder(invocationContext).build())
            .blockingGet();

    assertThat(response)
        .containsExactly("error", "Failed to list skills", "error_code", "SKILL_LOAD_ERROR");
  }

  @Test
  public void call_listSkillsTool_otherException() {
    TestBaseAgent testAgent =
        new TestBaseAgent(
            "test agent", "test agent", ImmutableList.of(), ImmutableList.of(), Flowable::empty);
    Session session = Session.builder("session").build();

    InvocationContext invocationContext = mock(InvocationContext.class);
    when(invocationContext.agent()).thenReturn(testAgent);
    when(invocationContext.session()).thenReturn(session);

    SkillSource skillSource = mock(SkillSource.class);
    RuntimeException expectedException = new RuntimeException("Unexpected error");
    when(skillSource.listFrontmatters()).thenReturn(Single.error(expectedException));

    ListSkillsTool listSkillsTool = new ListSkillsTool(skillSource);
    var single =
        listSkillsTool.runAsync(ImmutableMap.of(), ToolContext.builder(invocationContext).build());

    RuntimeException thrown = assertThrows(RuntimeException.class, single::blockingGet);

    assertThat(thrown).hasMessageThat().contains("Unexpected error");
  }

  @Test
  public void call_listSkillsTool_declaration() {
    ListSkillsTool listSkillsTool = new ListSkillsTool(mock(SkillSource.class));
    assertThat(listSkillsTool.declaration().get().name()).hasValue("list_skills");
  }
}
