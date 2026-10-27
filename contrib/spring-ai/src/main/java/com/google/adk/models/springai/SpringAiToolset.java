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
package com.google.adk.models.springai;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Exposes every Spring AI tool from a {@link ToolCallbackProvider} as an ADK {@link BaseToolset},
 * so a dependency-injected provider (for example a Spring bean) contributes its tools to an ADK
 * agent. Each callback is wrapped with {@link SpringAiTool}, and the provider is queried on every
 * {@link #getTools} call so tools it adds later are picked up.
 */
public final class SpringAiToolset implements BaseToolset {

  private final ToolCallbackProvider toolCallbackProvider;

  /**
   * Creates a toolset backed by {@code toolCallbackProvider}.
   *
   * @param toolCallbackProvider The Spring AI provider whose tools to expose
   */
  public SpringAiToolset(ToolCallbackProvider toolCallbackProvider) {
    this.toolCallbackProvider =
        Objects.requireNonNull(toolCallbackProvider, "toolCallbackProvider cannot be null");
  }

  @Override
  public Flowable<BaseTool> getTools(@Nullable ReadonlyContext readonlyContext) {
    return Flowable.defer(
        () ->
            Flowable.fromIterable(Arrays.asList(toolCallbackProvider.getToolCallbacks()))
                .map(SpringAiTool::new));
  }

  @Override
  public void close() {}
}
