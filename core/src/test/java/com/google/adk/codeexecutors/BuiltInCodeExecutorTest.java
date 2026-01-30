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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.models.LlmRequest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BuiltInCodeExecutorTest {

  private BuiltInCodeExecutor codeExecutor;

  @Before
  public void setUp() {
    codeExecutor = new BuiltInCodeExecutor();
  }

  @Test
  public void executeCode_throwsUnsupportedOperationException() {
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            codeExecutor.executeCode(
                InvocationContext.builder().build(),
                CodeExecutionInput.builder().code("code").build()));
  }

  @Test
  public void processLlmRequest_gemini2Model_addsCodeExecutionTool() {
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().model("gemini-2.0-flash-exp");
    codeExecutor.processLlmRequest(llmRequestBuilder);
    LlmRequest llmRequest = llmRequestBuilder.build();
    assertThat(llmRequest.config()).isPresent();
    assertThat(llmRequest.config().get().tools()).isPresent();
    assertThat(llmRequest.config().get().tools().get()).hasSize(1);
    assertThat(llmRequest.config().get().tools().get().get(0).codeExecution()).isPresent();
  }

  @Test
  public void processLlmRequest_nonGemini2Model_throwsIllegalArgumentException() {
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().model("gemini-1.5-pro");
    assertThrows(
        IllegalArgumentException.class, () -> codeExecutor.processLlmRequest(llmRequestBuilder));
  }
}
