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

import com.google.adk.models.LlmRequest;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Verifies that binary skill-resource expansion preserves neighboring request parts. */
@RunWith(JUnit4.class)
public final class LoadSkillResourceMixedPartsRegressionTest {

  @Test
  public void processLlmRequest_mixedParts_preservesOrderAndSiblingResponses() {
    LoadSkillResourceTool tool = new LoadSkillResourceTool(mock(SkillSource.class));
    ToolContext context = mock(ToolContext.class);
    byte[] bytes = {0, 1, 2, 3};
    Part leadingText = Part.fromText("Keep this context.");
    Part binaryResponse = response(tool.name(), "binary-call", bytes);
    Part textResponse = response(tool.name(), "text-call", "reference text");
    Part siblingResponse = response("other_tool", "sibling-call", "sibling result");
    Part existingImage = Part.fromBytes(new byte[] {4, 5}, "image/png");
    LlmRequest.Builder builder =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(
                            leadingText,
                            binaryResponse,
                            textResponse,
                            siblingResponse,
                            existingImage)
                        .build()));

    tool.processLlmRequest(builder, context).blockingAwait();

    List<Content> contents = builder.build().contents();
    assertThat(contents).hasSize(1);
    List<Part> parts = contents.get(0).parts().get();
    assertThat(parts).hasSize(6);
    assertThat(parts.get(0)).isEqualTo(leadingText);
    assertThat(parts.get(1).functionResponse().get().id()).hasValue("binary-call");
    assertThat(parts.get(1).functionResponse().get().response().get().get("content"))
        .isInstanceOf(String.class);
    assertThat(parts.get(2).inlineData().get().data().get()).isEqualTo(bytes);
    assertThat(parts.get(2).inlineData().get().mimeType()).hasValue("application/octet-stream");
    assertThat(parts.get(3)).isEqualTo(textResponse);
    assertThat(parts.get(4)).isEqualTo(siblingResponse);
    assertThat(parts.get(5)).isEqualTo(existingImage);

    LlmRequest.Builder nextRequest = LlmRequest.builder().contents(contents);
    tool.processLlmRequest(nextRequest, context).blockingAwait();
    assertThat(nextRequest.build().contents()).isEqualTo(contents);
  }

  @Test
  public void processLlmRequest_withoutBinaryResource_keepsAllParts() {
    LoadSkillResourceTool tool = new LoadSkillResourceTool(mock(SkillSource.class));
    Content content =
        Content.builder()
            .role("user")
            .parts(
                Part.fromText("Keep this context."),
                response(tool.name(), "text-call", "reference text"),
                response("other_tool", "sibling-call", "sibling result"))
            .build();
    LlmRequest.Builder builder = LlmRequest.builder().contents(ImmutableList.of(content));

    tool.processLlmRequest(builder, mock(ToolContext.class)).blockingAwait();

    assertThat(builder.build().contents()).containsExactly(content);
  }

  private static Part response(String name, String id, Object content) {
    return Part.builder()
        .functionResponse(
            FunctionResponse.builder()
                .name(name)
                .id(id)
                .response(
                    ImmutableMap.of(
                        "skill_name", "test-skill",
                        "file_path", "references/resource.dat",
                        "mime_type", "application/octet-stream",
                        "content", content))
                .build())
        .build();
  }
}
