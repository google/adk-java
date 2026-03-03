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

package com.google.adk.tools;

import com.google.adk.agents.ReadonlyContext;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Base interface for toolsets. */
public interface BaseToolset extends AutoCloseable {

  /**
   * Return all tools in the toolset based on the provided context.
   *
   * @param readonlyContext Context used to filter tools available to the agent.
   * @return A Single emitting a list of tools available under the specified context.
   */
  Flowable<BaseTool> getTools(ReadonlyContext readonlyContext);

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
   * Checks if a tool should be selected based on a filter.
   *
   * @param tool The tool to check.
   * @param toolFilter A ToolPredicate, a List of tool names, or null.
   * @param readonlyContext The context for checking the tool, or null.
   */
  default boolean isToolSelected(
      BaseTool tool, @Nullable Object toolFilter, @Nullable ReadonlyContext readonlyContext) {
    if (toolFilter == null) {
      return true;
    }

    if (toolFilter instanceof ToolPredicate toolPredicate) {
      return toolPredicate.test(tool, readonlyContext);
    }

    if (toolFilter instanceof List<?> toolNames) {
      return toolNames.contains(tool.name());
    }

    return false;
  }

  /**
   * @deprecated Use {@link #isToolSelected(BaseTool, Object, ReadonlyContext)} instead.
   */
  @Deprecated
  default boolean isToolSelected(
      BaseTool tool, Optional<Object> toolFilter, Optional<ReadonlyContext> readonlyContext) {
    return isToolSelected(tool, toolFilter.orElse(null), readonlyContext.orElse(null));
  }
}
