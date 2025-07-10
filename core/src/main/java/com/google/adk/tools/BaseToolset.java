package com.google.adk.tools;

import com.google.adk.agents.ReadonlyContext;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Optional;

/** Base interface for toolsets. */
public interface BaseToolset extends AutoCloseable {

  /**
   * Return all tools in the toolset based on the provided context.
   *
   * @param readonlyContext Context used to filter tools available to the agent.
   * @return A Flowable emitting a list of tools available under the specified context.
   */
  Flowable<List<BaseTool>> getTools(ReadonlyContext readonlyContext);

  /**
   * Performs cleanup and releases resources held by the toolset.
   *
   * <p>NOTE: This method is invoked, for example, at the end of an agent server's lifecycle or when
   * the toolset is no longer needed. Implementations should ensure that any open connections,
   * files, or other managed resources are properly released to prevent leaks.
   */
  @Override
  void close() throws Exception;

  /**
   * Helper method to be used by implementers that returns true if the given tool is in the provided
   * list of tools of if testing against the given ToolPredicate returns true (otherwise false).
   *
   * @param tool The tool to check.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   * @param readonlyContext The current context.
   * @return true if the tool is selected.
   */
  default boolean isToolSelected(
      BaseTool tool, Optional<Object> toolFilter, Optional<ReadonlyContext> readonlyContext) {
    if (toolFilter.isEmpty()) {
      return true;
    }
    Object filter = toolFilter.get();
    if (filter instanceof ToolPredicate toolPredicate) {
      return toolPredicate.test(tool, readonlyContext);
    }
    if (filter instanceof List) {
      @SuppressWarnings("unchecked")
      List<String> toolNames = (List<String>) filter;
      return toolNames.contains(tool.name());
    }
    return false;
  }
}
