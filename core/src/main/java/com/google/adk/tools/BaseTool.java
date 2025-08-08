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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.jspecify.annotations.Nullable;

/** The base class for all ADK tools. */
public abstract class BaseTool {
  private final String name;
  private final String description;
  private final boolean isLongRunning;

  protected BaseTool() {
    this(/* name= */ null, /* description= */ null, /* isLongRunning= */ false);
  }

  protected BaseTool(boolean isLongRunning) {
    this(/* name= */ null, /* description= */ null, isLongRunning);
  }

  protected BaseTool(@Nonnull String name, @Nonnull String description) {
    this(name, description, /* isLongRunning= */ false);
  }

  protected BaseTool(@Nonnull String name, @Nonnull String description, boolean isLongRunning) {
    this.name = name;
    this.description = description;
    this.isLongRunning = isLongRunning;
  }

  public String name() {
    return name != null
        ? name
        : declaration()
            .flatMap(FunctionDeclaration::name)
            .orElseThrow(() -> new IllegalStateException("Tool name is not set."));
  }

  public String description() {
    return description != null
        ? description
        : declaration()
            .flatMap(FunctionDeclaration::description)
            .orElseThrow(() -> new IllegalStateException("Tool description is not set."));
  }

  public boolean longRunning() {
    return isLongRunning;
  }

  /** Gets the {@link FunctionDeclaration} representation of this tool. */
  public Optional<FunctionDeclaration> declaration() {
    return Optional.empty();
  }

  /** Calls a tool. */
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    throw new UnsupportedOperationException("This method is not implemented.");
  }

  /**
   * Processes the outgoing {@link LlmRequest.Builder}.
   *
   * <p>This implementation adds the current tool's {@link #declaration()} to the {@link
   * GenerateContentConfig} within the builder. If a tool with function declarations already exists,
   * the current tool's declaration is merged into it. Otherwise, a new tool definition with the
   * current tool's declaration is created. The current tool itself is also added to the builder's
   * internal list of tools. Override this method for processing the outgoing request.
   */
  @CanIgnoreReturnValue
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    if (declaration().isEmpty()) {
      return Completable.complete();
    }

    llmRequestBuilder.appendTools(ImmutableList.of(this));

    LlmRequest llmRequest = llmRequestBuilder.build();
    ImmutableList<Tool> toolsWithoutFunctionDeclarations =
        findToolsWithoutFunctionDeclarations(llmRequest);
    Tool toolWithFunctionDeclarations = findToolWithFunctionDeclarations(llmRequest);
    // If LlmRequest GenerateContentConfig already has a function calling tool,
    // merge the function declarations.
    // Otherwise, add a new tool definition with function calling declaration..
    if (toolWithFunctionDeclarations == null) {
      toolWithFunctionDeclarations =
          Tool.builder().functionDeclarations(ImmutableList.of(declaration().get())).build();
    } else {
      toolWithFunctionDeclarations =
          toolWithFunctionDeclarations.toBuilder()
              .functionDeclarations(
                  ImmutableList.<FunctionDeclaration>builder()
                      .addAll(
                          toolWithFunctionDeclarations
                              .functionDeclarations()
                              .orElse(ImmutableList.of()))
                      .add(declaration().get())
                      .build())
              .build();
    }
    // Patch the GenerateContentConfig with the new tool definition.
    GenerateContentConfig generateContentConfig =
        llmRequest
            .config()
            .map(GenerateContentConfig::toBuilder)
            .orElse(GenerateContentConfig.builder())
            .tools(
                new ImmutableList.Builder<Tool>()
                    .addAll(toolsWithoutFunctionDeclarations)
                    .add(toolWithFunctionDeclarations)
                    .build())
            .build();
    llmRequestBuilder.config(generateContentConfig);
    return Completable.complete();
  }

  /**
   * Finds a tool in GenerateContentConfig that has function calling declarations, or returns null
   * otherwise.
   */
  private static @Nullable Tool findToolWithFunctionDeclarations(LlmRequest llmRequest) {
    return llmRequest
        .config()
        .flatMap(config -> config.tools())
        .flatMap(
            tools -> tools.stream().filter(t -> t.functionDeclarations().isPresent()).findFirst())
        .orElse(null);
  }

  /** Finds all tools in GenerateContentConfig that do not have function calling declarations. */
  private static ImmutableList<Tool> findToolsWithoutFunctionDeclarations(LlmRequest llmRequest) {
    return llmRequest
        .config()
        .flatMap(config -> config.tools())
        .map(
            tools ->
                tools.stream()
                    .filter(t -> t.functionDeclarations().isEmpty())
                    .collect(toImmutableList()))
        .orElse(ImmutableList.of());
  }
}
