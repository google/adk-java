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
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.InMemorySkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LoadSkillResourceToolTest {

  @Test
  public void call_loadSkillResourceTool_reference_success() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "references/my_doc.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "file_path", "references/my_doc.md",
            "mime_type", "text/markdown",
            "content", "doc content");
  }

  @Test
  public void call_loadSkillResourceTool_asset_success() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "assets/template.txt"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "file_path", "assets/template.txt",
            "mime_type", "text/plain",
            "content", "asset content");
  }

  @Test
  public void call_loadSkillResourceTool_script_success() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "scripts/setup.sh"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "file_path", "scripts/setup.sh",
            "mime_type", "application/x-sh",
            "content", "echo hello");
  }

  @Test
  public void call_loadSkillResourceTool_streamDetection_success() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "assets/data_no_ext"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "skill_name", "test-skill",
            "file_path", "assets/data_no_ext",
            "mime_type", "application/xml",
            "content", "<?xml version=\"1.0\"?><root/>");
  }

  @Test
  public void call_loadSkillResourceTool_binaryReference_detected() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    ToolContext toolContext = createToolContext();
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "references/binary.dat"),
                toolContext)
            .blockingGet();

    Part partFunctionResponse =
        Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .id(toolContext.functionCallId().orElse(""))
                    .name(loadSkillResourceTool.name())
                    .response(response)
                    .build())
            .build();

    // Binary data is added as separate part in the next request to LLM
    LlmRequest.Builder builder =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.builder().role("user").parts(partFunctionResponse).build()));
    loadSkillResourceTool.processLlmRequest(builder, toolContext).blockingAwait();

    List<Content> contents = builder.build().contents();
    assertThat(contents).hasSize(1);
    List<Part> parts = contents.get(0).parts().get();
    assertThat(parts).hasSize(2);

    FunctionResponse updatedFunctionResponse = parts.get(0).functionResponse().get();
    assertThat(updatedFunctionResponse.response().get())
        .containsExactly(
            "skill_name",
            "test-skill",
            "file_path",
            "references/binary.dat",
            "content",
            "Binary file detected. The content has been included in the next part of the function"
                + " response for you to analyze.");

    Part binaryPart = parts.get(1);
    assertThat(binaryPart.inlineData().get().mimeType()).hasValue("application/octet-stream");
    assertThat(binaryPart.inlineData().get().data().get()).isEqualTo(new byte[] {0, 1, 2, 3});
  }

  @Test
  public void call_loadSkillResourceTool_nonBinaryReference_notChanged() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    ToolContext toolContext = createToolContext();
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "references/my_doc.md"),
                toolContext)
            .blockingGet();

    Part partFunctionResponse =
        Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .id(toolContext.functionCallId().orElse(""))
                    .name(loadSkillResourceTool.name())
                    .response(response)
                    .build())
            .build();

    LlmRequest.Builder builder =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.builder().role("user").parts(partFunctionResponse).build()));
    List<Content> expectedContents = builder.build().contents();

    loadSkillResourceTool.processLlmRequest(builder, toolContext).blockingAwait();

    assertThat(builder.build().contents()).isEqualTo(expectedContents);
  }

  @Test
  public void call_loadSkillResourceTool_missingSkillName() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(ImmutableMap.of("file_path", "references/my_doc.md"), createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Skill name is required.",
            "error_code", "MISSING_SKILL_NAME");
  }

  @Test
  public void call_loadSkillResourceTool_missingPath() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
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
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "other-skill", "file_path", "references/my_doc.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Skill not found: other-skill",
            "error_code", "SKILL_NOT_FOUND");
  }

  @Test
  public void call_loadSkillResourceTool_invalidPathPrefix() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "invalid/my_doc.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Path must start with 'references/', 'assets/', or 'scripts/'.",
            "error_code", "INVALID_RESOURCE_PATH");
  }

  @Test
  public void call_loadSkillResourceTool_resourceNotFound() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(createTestSkillSource());
    Map<String, Object> response =
        loadSkillResourceTool
            .runAsync(
                ImmutableMap.of("skill_name", "test-skill", "file_path", "references/missing.md"),
                createToolContext())
            .blockingGet();

    assertThat(response)
        .containsExactly(
            "error", "Resource not found: references/missing.md",
            "error_code", "RESOURCE_NOT_FOUND");
  }

  @Test
  public void call_loadSkillResourceTool_declaration() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(mock(SkillSource.class));
    assertThat(loadSkillResourceTool.declaration().get().name()).hasValue("load_skill_resource");
  }

  @Test
  public void call_loadSkillResourceTool_processLlmRequest_emptyContents() {
    LoadSkillResourceTool loadSkillResourceTool =
        new LoadSkillResourceTool(mock(SkillSource.class));
    LlmRequest.Builder builder = LlmRequest.builder().contents(ImmutableList.of());
    loadSkillResourceTool.processLlmRequest(builder, createToolContext()).blockingAwait();
    assertThat(builder.build().contents()).isEmpty();
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

  private SkillSource createTestSkillSource() {
    return InMemorySkillSource.builder()
        .skill("test-skill")
        .frontmatter(Frontmatter.builder().name("test-skill").description("test skill").build())
        .instructions("Test instructions")
        .addResource("references/my_doc.md", "doc content".getBytes(UTF_8))
        .addResource("references/binary.dat", new byte[] {0, 1, 2, 3})
        .addResource("assets/template.txt", "asset content".getBytes(UTF_8))
        .addResource("scripts/setup.sh", "echo hello".getBytes(UTF_8))
        .addResource("assets/data_no_ext", "<?xml version=\"1.0\"?><root/>".getBytes(UTF_8))
        .build();
  }
}
