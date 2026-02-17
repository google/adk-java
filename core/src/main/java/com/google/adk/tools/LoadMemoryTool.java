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
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Method;

/**
 * A tool that loads memory for the current user.
 *
 * <p>NOTE: Currently this tool only uses text part from the memory.
 */
public class LoadMemoryTool extends FunctionTool {

  private static Method getLoadMemoryMethod() {
    try {
      return LoadMemoryTool.class.getMethod("loadMemory", String.class, ToolContext.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Failed to load memory method.", e);
    }
  }

  public LoadMemoryTool() {
    super(
        /* instance= */ null,
        getLoadMemoryMethod(),
        /* isLongRunning= */ false,
        /* requireConfirmation= */ false);
  }

  /**
   * Loads the memory for the current user.
   *
   * @param query The query to load memory for.
   * @return A list of memory results.
   */
  public static Single<LoadMemoryResponse> loadMemory(
      @Annotations.Schema(name = "query") String query, ToolContext toolContext) {
    return toolContext
        .searchMemory(query)
        .map(searchMemoryResponse -> new LoadMemoryResponse(searchMemoryResponse.memories()));
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
You have memory. You can use it to answer questions. If any questions need
you to look up the memory, you should call loadMemory function with a query.
""")));
  }
}
