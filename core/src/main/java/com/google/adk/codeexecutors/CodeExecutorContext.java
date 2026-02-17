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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.codeexecutors.CodeExecutionUtils.File;
import com.google.common.collect.ImmutableMap;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The persistent context used to configure the code executor. */
@SuppressWarnings("unchecked")
public class CodeExecutorContext {

  private static final String CONTEXT_KEY = "_code_execution_context";
  private static final String SESSION_ID_KEY = "execution_session_id";
  private static final String PROCESSED_FILE_NAMES_KEY = "processed_input_files";
  private static final String INPUT_FILE_KEY = "_code_executor_input_files";
  private static final String ERROR_COUNT_KEY = "_code_executor_error_counts";
  private static final String CODE_EXECUTION_RESULTS_KEY = "_code_execution_results";

  private final Map<String, Object> sessionState;
  private final Map<String, Object> context;
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  /**
   * Initializes the code executor context.
   *
   * @param sessionState The session state to get the code executor context from.
   */
  public CodeExecutorContext(Map<String, Object> sessionState) {
    this.sessionState = sessionState;
    this.context = getCodeExecutorContext(sessionState);
  }

  /**
   * Gets the state delta to update in the persistent session state.
   *
   * @return The state delta to update in the persistent session state.
   */
  public Map<String, Object> getStateDelta() {
    Map<String, Object> contextToUpdate = new HashMap<>(this.context);
    return ImmutableMap.of(CONTEXT_KEY, contextToUpdate);
  }

  /**
   * Gets the session ID for the code executor.
   *
   * @return The session ID for the code executor context.
   */
  public Optional<String> getExecutionId() {
    return Optional.ofNullable((String) this.context.get(SESSION_ID_KEY));
  }

  /**
   * Sets the session ID for the code executor.
   *
   * @param sessionId The session ID for the code executor.
   */
  public void setExecutionId(String sessionId) {
    this.context.put(SESSION_ID_KEY, sessionId);
  }

  /**
   * Gets the processed file names from the session state.
   *
   * @return A list of processed file names in the code executor context.
   */
  public List<String> getProcessedFileNames() {
    return (List<String>)
        this.context.computeIfAbsent(PROCESSED_FILE_NAMES_KEY, unused -> new ArrayList<>());
  }

  /**
   * Adds the processed file name to the session state.
   *
   * @param fileNames The processed file names to add to the session state.
   */
  public void addProcessedFileNames(List<String> fileNames) {
    List<String> processedFileNames =
        (List<String>)
            this.context.computeIfAbsent(PROCESSED_FILE_NAMES_KEY, unused -> new ArrayList<>());
    processedFileNames.addAll(fileNames);
  }

  /**
   * Gets the code executor input file names from the session state.
   *
   * @return A list of input files in the code executor context.
   */
  public List<File> getInputFiles() {
    List<Map<String, Object>> fileMaps =
        (List<Map<String, Object>>)
            this.sessionState.getOrDefault(INPUT_FILE_KEY, new ArrayList<>());
    return fileMaps.stream()
        .map(fileMap -> objectMapper.convertValue(fileMap, File.class))
        .collect(toImmutableList());
  }

  /**
   * Adds the input files to the code executor context.
   *
   * @param inputFiles The input files to add to the code executor context.
   */
  public void addInputFiles(List<File> inputFiles) {
    List<Map<String, Object>> fileMaps =
        (List<Map<String, Object>>)
            this.sessionState.computeIfAbsent(INPUT_FILE_KEY, unused -> new ArrayList<>());
    for (File inputFile : inputFiles) {
      fileMaps.add(
          objectMapper.convertValue(inputFile, new TypeReference<Map<String, Object>>() {}));
    }
  }

  /** Removes the input files and processed file names to the code executor context. */
  public void clearInputFiles() {
    if (this.sessionState.containsKey(INPUT_FILE_KEY)) {
      this.sessionState.put(INPUT_FILE_KEY, new ArrayList<>());
    }
    if (this.context.containsKey(PROCESSED_FILE_NAMES_KEY)) {
      this.context.put(PROCESSED_FILE_NAMES_KEY, new ArrayList<>());
    }
  }

  /**
   * Gets the error count from the session state.
   *
   * @param invocationId The invocation ID to get the error count for.
   * @return The error count for the given invocation ID.
   */
  public int getErrorCount(String invocationId) {
    Map<String, Integer> errorCounts =
        (Map<String, Integer>) this.sessionState.get(ERROR_COUNT_KEY);
    if (errorCounts == null) {
      return 0;
    }
    return errorCounts.getOrDefault(invocationId, 0);
  }

  /**
   * Increments the error count from the session state.
   *
   * @param invocationId The invocation ID to increment the error count for.
   */
  public void incrementErrorCount(String invocationId) {
    Map<String, Integer> errorCounts =
        (Map<String, Integer>)
            this.sessionState.computeIfAbsent(ERROR_COUNT_KEY, unused -> new HashMap<>());
    errorCounts.put(invocationId, getErrorCount(invocationId) + 1);
  }

  /**
   * Resets the error count from the session state.
   *
   * @param invocationId The invocation ID to reset the error count for.
   */
  public void resetErrorCount(String invocationId) {
    Map<String, Integer> errorCounts =
        (Map<String, Integer>) this.sessionState.get(ERROR_COUNT_KEY);
    if (errorCounts != null) {
      errorCounts.remove(invocationId);
    }
  }

  /**
   * Updates the code execution result.
   *
   * @param invocationId The invocation ID to update the code execution result for.
   * @param code The code to execute.
   * @param resultStdout The standard output of the code execution.
   * @param resultStderr The standard error of the code execution.
   */
  public void updateCodeExecutionResult(
      String invocationId, String code, String resultStdout, String resultStderr) {
    Map<String, List<Map<String, Object>>> codeExecutionResults =
        (Map<String, List<Map<String, Object>>>)
            this.sessionState.computeIfAbsent(
                CODE_EXECUTION_RESULTS_KEY, unused -> new HashMap<>());
    List<Map<String, Object>> resultsForInvocation =
        codeExecutionResults.computeIfAbsent(invocationId, unused -> new ArrayList<>());
    Map<String, Object> newResult = new HashMap<>();
    newResult.put("code", code);
    newResult.put("result_stdout", resultStdout);
    newResult.put("result_stderr", resultStderr);
    newResult.put("timestamp", InstantSource.system().instant().getEpochSecond());
    resultsForInvocation.add(newResult);
  }

  private Map<String, Object> getCodeExecutorContext(Map<String, Object> sessionState) {
    return (Map<String, Object>)
        sessionState.computeIfAbsent(CONTEXT_KEY, unused -> new HashMap<>());
  }
}
