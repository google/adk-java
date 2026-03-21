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

package com.google.adk.tools;

import com.google.adk.models.LlmRequest;
import com.google.adk.reasoning.BaseReasoningBankService;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Method;

/**
 * A tool that loads reasoning strategies for the current task.
 *
 * <p>This tool allows agents to retrieve relevant reasoning strategies from the ReasoningBank based
 * on a query describing the current task. The retrieved strategies provide structured
 * problem-solving approaches that can guide the agent's reasoning.
 *
 * <p>Based on the ReasoningBank paper (arXiv:2509.25140).
 */
public class LoadReasoningStrategyTool extends FunctionTool {

  /** Handler that holds the service reference and implements the tool method. */
  public static class ReasoningBankHandler {
    private final BaseReasoningBankService reasoningBankService;
    private final String appName;

    ReasoningBankHandler(BaseReasoningBankService reasoningBankService, String appName) {
      this.reasoningBankService = reasoningBankService;
      this.appName = appName;
    }

    /**
     * Loads reasoning strategies that match the given query.
     *
     * @param query A description of the task or problem to find strategies for.
     * @param toolContext The tool context (required by FunctionTool contract).
     * @return A response containing matching reasoning strategies.
     */
    public Single<LoadReasoningStrategyResponse> loadReasoningStrategy(
        @Annotations.Schema(name = "query", description = "A description of the task or problem")
            String query,
        ToolContext toolContext) {
      return reasoningBankService
          .searchStrategies(appName, query)
          .map(response -> new LoadReasoningStrategyResponse(response.strategies()));
    }
  }

  private static Method getLoadReasoningStrategyMethod() {
    try {
      return ReasoningBankHandler.class.getMethod(
          "loadReasoningStrategy", String.class, ToolContext.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Failed to find loadReasoningStrategy method.", e);
    }
  }

  /**
   * Creates a new LoadReasoningStrategyTool.
   *
   * @param reasoningBankService The reasoning bank service to search for strategies.
   * @param appName The application name used to scope strategy storage and retrieval.
   */
  public LoadReasoningStrategyTool(BaseReasoningBankService reasoningBankService, String appName) {
    super(
        new ReasoningBankHandler(reasoningBankService, appName),
        getLoadReasoningStrategyMethod(),
        /* isLongRunning= */ false,
        /* requireConfirmation= */ false);
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    return super.processLlmRequest(llmRequestBuilder, toolContext)
        .doOnComplete(
            () ->
                llmRequestBuilder.appendInstructions(
                    ImmutableList.of(
"""
You have access to a reasoning bank containing proven problem-solving strategies.
When facing a complex task, you can call loadReasoningStrategy with a description
of your task to retrieve relevant reasoning approaches. Each strategy includes
problem patterns it addresses and ordered reasoning steps to follow.
""")));
  }
}
