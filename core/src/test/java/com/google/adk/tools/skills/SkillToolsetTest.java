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

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.InMemorySkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SkillToolsetTest {

  @Test
  public void getTools_returnsCoreTools() throws Exception {
    SkillSource mockSkillSource = mock(SkillSource.class);
    try (SkillToolset toolSet = new SkillToolset(mockSkillSource)) {
      Flowable<BaseTool> tools = toolSet.getTools(null);
      List<BaseTool> baseTools = tools.toList().blockingGet();

      assertThat(baseTools)
          .comparingElementsUsing(Correspondence.transforming(BaseTool::name, "Tool name"))
          .containsExactly("list_skills", "load_skill", "load_skill_resource");
    }
  }

  @Test
  public void getTools_withInMemorySkills() throws Exception {
    SkillSource skillSource =
        InMemorySkillSource.builder()
            .skill("test-skill")
            .frontmatter(Frontmatter.builder().name("test-skill").description("test skill").build())
            .instructions("Test instructions")
            .build();
    try (SkillToolset toolSet = new SkillToolset(skillSource)) {

      Flowable<BaseTool> tools = toolSet.getTools(null);
      List<BaseTool> baseTools = tools.toList().blockingGet();

      assertThat(baseTools)
          .comparingElementsUsing(Correspondence.transforming(BaseTool::name, "Tool name"))
          .containsExactly("list_skills", "load_skill", "load_skill_resource");
    }
  }

  @Test
  public void processLlmRequest_addsInstructions() throws Exception {
    SkillSource skillSource =
        InMemorySkillSource.builder()
            .skill("test-skill")
            .frontmatter(Frontmatter.builder().name("test-skill").description("test skill").build())
            .instructions("Test instructions")
            .build();
    try (SkillToolset toolSet = new SkillToolset(skillSource)) {

      LlmRequest.Builder requestBuilder = LlmRequest.builder();
      ToolContext mockToolContext = mock(ToolContext.class);

      toolSet.processLlmRequest(requestBuilder, mockToolContext).blockingAwait();

      LlmRequest request = requestBuilder.build();
      ImmutableList<String> instructions = request.getSystemInstructions();

      assertThat(instructions).isNotEmpty();
      String instruction = instructions.get(0);
      assertThat(instruction)
          .contains("You can use specialized 'skills' to help you with complex tasks");
      assertThat(instruction).contains("<skill>");
      assertThat(instruction).contains("test-skill");
    }
  }

  @Test
  public void processLlmRequest_withCustomSystemInstruction_addsCustomInstructions()
      throws Exception {
    SkillSource skillSource =
        InMemorySkillSource.builder()
            .skill("test-skill")
            .frontmatter(Frontmatter.builder().name("test-skill").description("test skill").build())
            .instructions("Test instructions")
            .build();
    String customInstruction = "Custom system instruction for testing.";
    try (SkillToolset toolSet = new SkillToolset(skillSource, customInstruction)) {

      LlmRequest.Builder requestBuilder = LlmRequest.builder();
      ToolContext mockToolContext = mock(ToolContext.class);

      toolSet.processLlmRequest(requestBuilder, mockToolContext).blockingAwait();

      LlmRequest request = requestBuilder.build();
      ImmutableList<String> instructions = request.getSystemInstructions();

      assertThat(instructions).isNotEmpty();
      String instruction = instructions.get(0);
      assertThat(instruction).contains(customInstruction);
      assertThat(instruction).contains("<skill>");
      assertThat(instruction).contains("test-skill");
    }
  }

  @Test
  public void baseToolset_defaultProcessLlmRequest() throws Exception {
    try (BaseToolset baseToolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(ReadonlyContext context) {
            return Flowable.empty();
          }

          @Override
          public void close() {}
        }) {
      baseToolset.processLlmRequest(LlmRequest.builder(), mock(ToolContext.class)).blockingAwait();
    }
  }
}
