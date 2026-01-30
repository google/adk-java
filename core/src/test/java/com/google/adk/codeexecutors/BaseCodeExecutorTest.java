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

import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BaseCodeExecutorTest {

  private static class TestCodeExecutor extends BaseCodeExecutor {
    @Override
    public CodeExecutionResult executeCode(
        InvocationContext invocationContext, CodeExecutionInput codeExecutionInput) {
      return null;
    }
  }

  @Test
  public void baseCodeExecutor_defaultValues() {
    TestCodeExecutor codeExecutor = new TestCodeExecutor();
    assertThat(codeExecutor.optimizeDataFile()).isFalse();
    assertThat(codeExecutor.stateful()).isFalse();
    assertThat(codeExecutor.errorRetryAttempts()).isEqualTo(2);
    assertThat(codeExecutor.codeBlockDelimiters())
        .isEqualTo(
            ImmutableList.of(
                ImmutableList.of("```tool_code\n", "\n```"),
                ImmutableList.of("```python\n", "\n```")));
    assertThat(codeExecutor.executionResultDelimiters())
        .isEqualTo(ImmutableList.of("```tool_output\n", "\n```"));
  }
}
