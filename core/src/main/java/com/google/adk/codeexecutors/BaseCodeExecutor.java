/*
 * Copyright 2025 Google LLC
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

import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.common.collect.ImmutableList;

/**
 * Abstract base class for all code executors.
 *
 * <p>The code executor allows the agent to execute code blocks from model responses and incorporate
 * the execution results into the final response.
 */
public abstract class BaseCodeExecutor extends JsonBaseModel {

  private static final ImmutableList<ImmutableList<String>> CODE_BLOCK_DELIMITERS =
      ImmutableList.of(
          ImmutableList.of("```tool_code\n", "\n```"), ImmutableList.of("```python\n", "\n```"));
  private static final ImmutableList<String> EXECUTION_RESULT_DELIMITERS =
      ImmutableList.of("```tool_output\n", "\n```");

  /**
   * If true, extract and process data files from the model request and attach them to the code
   * executor.
   *
   * <p>Supported data file MimeTypes are [text/csv]. Default to False.
   */
  public boolean optimizeDataFile() {
    return false;
  }

  /** Whether the code executor is stateful. Default to False. */
  public boolean stateful() {
    return false;
  }

  /**
   * The number of attempts to retry on consecutive code execution errors.
   *
   * <p>Default to 2.
   */
  public int errorRetryAttempts() {
    return 2;
  }

  /**
   * The list of the enclosing delimiters to identify the code blocks.
   *
   * <p>Each inner list contains a pair of start and end delimiters. This supports multiple pairs of
   * delimiters.
   *
   * <p>For example, the delimiter ('```python\n', '\n```') can be used to identify code blocks with
   * the following format:
   *
   * <p>```python
   *
   * <p>print("hello")
   *
   * <p>```
   */
  public ImmutableList<ImmutableList<String>> codeBlockDelimiters() {
    return CODE_BLOCK_DELIMITERS;
  }

  /** The delimiters to format the code execution result. */
  public ImmutableList<String> executionResultDelimiters() {
    return EXECUTION_RESULT_DELIMITERS;
  }

  /**
   * Executes code and return the code execution result.
   *
   * <p>This method may perform blocking operations.
   *
   * @param invocationContext The invocation context of the code execution.
   * @param codeExecutionInput The code execution input.
   * @return The code execution result.
   */
  public abstract CodeExecutionResult executeCode(
      InvocationContext invocationContext, CodeExecutionInput codeExecutionInput);
}
