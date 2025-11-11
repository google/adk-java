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
