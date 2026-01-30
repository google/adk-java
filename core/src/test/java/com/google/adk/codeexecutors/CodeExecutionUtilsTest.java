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

package com.google.adk.codeexecutors;

import static com.google.common.truth.Truth8.assertThat;

import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.CodeExecutionResult.Outcome;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CodeExecutionUtilsTest {

  @Test
  public void buildCodeExecutionResultPart_success() {
    CodeExecutionResult result = CodeExecutionResult.builder().stdout("output").build();
    Part part = CodeExecutionUtils.buildCodeExecutionResultPart(result);
    assertThat(part.codeExecutionResult()).isPresent();
    assertThat(part.codeExecutionResult().get().outcome()).hasValue(Outcome.OK);
    assertThat(part.codeExecutionResult().get().output())
        .hasValue("Code execution result:\noutput\n");
  }

  @Test
  public void buildCodeExecutionResultPart_failure() {
    CodeExecutionResult result = CodeExecutionResult.builder().stderr("error").build();
    Part part = CodeExecutionUtils.buildCodeExecutionResultPart(result);
    assertThat(part.codeExecutionResult()).isPresent();
    assertThat(part.codeExecutionResult().get().outcome()).hasValue(Outcome.FAILED);
    assertThat(part.codeExecutionResult().get().output()).hasValue("error");
  }

  @Test
  public void buildExecutableCodePart_success() {
    Part part = CodeExecutionUtils.buildExecutableCodePart("code");
    assertThat(part.executableCode()).isPresent();
    assertThat(part.executableCode().get().code()).hasValue("code");
  }

  @Test
  public void convertCodeExecutionParts_executableCode() {
    Content content =
        Content.builder()
            .parts(ImmutableList.of(CodeExecutionUtils.buildExecutableCodePart("code")))
            .role("model")
            .build();
    Content newContent =
        CodeExecutionUtils.convertCodeExecutionParts(
            content, ImmutableList.of("```", "```"), ImmutableList.of());
    assertThat(newContent.parts().get()).hasSize(1);
    assertThat(newContent.parts().get().get(0).text()).hasValue("```code```");
  }

  @Test
  public void convertCodeExecutionParts_codeExecutionResult() {
    Content content =
        Content.builder()
            .parts(
                ImmutableList.of(
                    CodeExecutionUtils.buildCodeExecutionResultPart(
                        CodeExecutionResult.builder().stdout("output").build())))
            .role("model")
            .build();
    Content newContent =
        CodeExecutionUtils.convertCodeExecutionParts(
            content, ImmutableList.of(), ImmutableList.of("'''", "'''"));
    assertThat(newContent.parts().get()).hasSize(1);
    assertThat(newContent.parts().get().get(0).text().get()).contains("'''");
    assertThat(newContent.parts().get().get(0).text().get()).contains("output");
  }

  @Test
  public void extractCodeAndTruncateContent_executableCode() {
    Content.Builder contentBuilder =
        Content.builder()
            .parts(ImmutableList.of(CodeExecutionUtils.buildExecutableCodePart("code")))
            .role("model");
    Optional<String> code =
        CodeExecutionUtils.extractCodeAndTruncateContent(
            contentBuilder, ImmutableList.of(ImmutableList.of("```", "```")));
    assertThat(code).hasValue("code");
    assertThat(contentBuilder.build().parts().get()).hasSize(1);
  }

  @Test
  public void extractCodeAndTruncateContent_text() {
    Content.Builder contentBuilder =
        Content.builder().parts(ImmutableList.of(Part.fromText("```code```"))).role("model");
    Optional<String> code =
        CodeExecutionUtils.extractCodeAndTruncateContent(
            contentBuilder, ImmutableList.of(ImmutableList.of("```", "```")));
    assertThat(code).hasValue("code");
    assertThat(contentBuilder.build().parts().get()).hasSize(1);
    assertThat(contentBuilder.build().parts().get().get(0).executableCode()).isPresent();
  }
}
