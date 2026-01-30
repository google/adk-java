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

import com.google.adk.codeexecutors.CodeExecutionUtils.File;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CodeExecutorContextTest {

  @Test
  public void getProcessedFileNames_returnsImmutableList() {
    CodeExecutorContext context = new CodeExecutorContext(new HashMap<>());
    context.addProcessedFileNames(Arrays.asList("file1.txt"));
    ImmutableList<String> processedFileNames = context.getProcessedFileNames();
    assertThat(processedFileNames).containsExactly("file1.txt");
  }

  @Test
  public void getInputFiles_returnsImmutableList() {
    Map<String, Object> sessionState = new HashMap<>();
    sessionState.put(
        "_code_executor_input_files",
        ImmutableList.of(
            ImmutableMap.of("name", "file2.txt", "content", "content", "mimeType", "text/plain")));
    CodeExecutorContext context = new CodeExecutorContext(sessionState);
    ImmutableList<File> inputFiles = context.getInputFiles();
    assertThat(inputFiles)
        .containsExactly(
            File.builder().name("file2.txt").content("content").mimeType("text/plain").build());
  }
}
