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
 * A tool that loads relevant reasoning memory items for the current task.
 *
 * <p>This implements the "retrieve" step of the ReasoningBank loop: given a description of the
 * current task, the tool queries the {@link BaseReasoningBankService} for memory items (titles,
 * descriptions, and distilled reasoning content) that can steer the agent — including preventative
 * lessons extracted from past failures.
 *
 * <p>Based on Ouyang et al. "ReasoningBank: Scaling Agent Self-Evolving with Reasoning Memory"
 * (ICLR 2026, <a href="https://arxiv.org/abs/2509.25140">arXiv:2509.25140</a>).
 */
public class LoadReasoningMemoryTool extends FunctionTool {

  /** Handler that holds the service reference and implements the tool method. */
  public static class ReasoningBankHandler {
    private final BaseReasoningBankService reasoningBankService;
    private final String appName;

    ReasoningBankHandler(BaseReasoningBankService reasoningBankService, String appName) {
      this.reasoningBankService = reasoningBankService;
      this.appName = appName;
    }

    /**
     * Loads memory items that match the given query.
     *
     * @param query a description of the task or problem being solved.
     * @param toolContext the tool context (required by FunctionTool contract).
     */
    public Single<LoadReasoningMemoryResponse> loadReasoningMemory(
        @Annotations.Schema(name = "query", description = "A description of the task or problem")
            String query,
        ToolContext toolContext) {
      return reasoningBankService
          .searchMemoryItems(appName, query)
          .map(response -> new LoadReasoningMemoryResponse(response.memoryItems()));
    }
  }

  private static Method getLoadReasoningMemoryMethod() {
    try {
      return ReasoningBankHandler.class.getMethod(
          "loadReasoningMemory", String.class, ToolContext.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Failed to find loadReasoningMemory method.", e);
    }
  }

  /**
   * Creates a new {@code LoadReasoningMemoryTool}.
   *
   * @param reasoningBankService the reasoning bank service to search.
   * @param appName the application name used to scope storage and retrieval.
   */
  public LoadReasoningMemoryTool(BaseReasoningBankService reasoningBankService, String appName) {
    super(
        new ReasoningBankHandler(reasoningBankService, appName),
        getLoadReasoningMemoryMethod(),
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
You have access to a ReasoningBank containing distilled memory items learned from past
task executions (both successful and failed). When facing a complex task, call
loadReasoningMemory with a description of the task to retrieve relevant items. Each
item has a title, a one-sentence description, and reasoning content — some items
encode preventative lessons from past failures, so treat them as guardrails.
""")));
  }
}
