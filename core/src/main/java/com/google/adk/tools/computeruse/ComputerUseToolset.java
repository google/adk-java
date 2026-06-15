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

package com.google.adk.tools.computeruse;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.ComputerUse;
import com.google.genai.types.Environment;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A toolset that provides computer use capabilities.
 *
 * <p>It automatically discovers and wraps methods from a {@link BaseComputer} implementation.
 */
public class ComputerUseToolset implements BaseToolset {

  private static final Logger logger = LoggerFactory.getLogger(ComputerUseToolset.class);

  private static final ImmutableSet<String> EXCLUDED_METHODS =
      ImmutableSet.of(
          "screenSize",
          "environment",
          "close",
          "initialize",
          "currentState",
          "getClass",
          "equals",
          "hashCode",
          "toString",
          "wait",
          "notify",
          "notifyAll");

  private final BaseComputer computer;
  private final int[] virtualScreenSize;
  private List<BaseTool> tools;
  private boolean initialized = false;

  public ComputerUseToolset(BaseComputer computer) {
    this(computer, new int[] {1000, 1000});
  }

  public ComputerUseToolset(BaseComputer computer, int[] virtualScreenSize) {
    this.computer = computer;
    this.virtualScreenSize = virtualScreenSize;
  }

  private synchronized Completable ensureInitialized() {
    if (initialized) {
      return Completable.complete();
    }
    return computer
        .initialize()
        .doOnComplete(
            () -> {
              initialized = true;
            });
  }

  @Override
  public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
    return ensureInitialized()
        .andThen(computer.screenSize())
        .flatMapPublisher(
            actualScreenSize -> {
              if (tools == null) {
                tools = new ArrayList<>();
                for (Method method : BaseComputer.class.getMethods()) {
                  if (!EXCLUDED_METHODS.contains(method.getName())) {
                    tools.add(
                        new ComputerUseTool(computer, method, actualScreenSize, virtualScreenSize));
                  }
                }
              }
              return Flowable.fromIterable(tools);
            });
  }

  @Override
  public void close() throws Exception {
    computer.close().blockingAwait();
  }

  /** Adds computer use configuration to the LLM request. */
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    return getTools(null) // Fetch tools to ensure they are added to the list
        .toList()
        .flatMapCompletable(
            tools -> {
              return Completable.concat(
                      tools.stream()
                          .map(t -> t.processLlmRequest(llmRequestBuilder, toolContext))
                          .collect(toImmutableList()))
                  .andThen(
                      computer
                          .environment()
                          .flatMapCompletable(
                              env -> {
                                configureComputerUseIfNeeded(llmRequestBuilder, env);
                                return Completable.complete();
                              }));
            });
  }

  /**
   * Returns the {@link Environment.Known} enum for the given {@link ComputerEnvironment}. If the
   * computer environment is not found or not supported, defaults to {@link
   * Environment.Known.ENVIRONMENT_BROWSER}.
   *
   * @param computerEnvironment The {@link ComputerEnvironment} to convert.
   * @return The corresponding {@link Environment.Known} enum.
   */
  private static Environment.Known getEnvironment(ComputerEnvironment computerEnvironment) {
    try {
      return Environment.Known.valueOf(computerEnvironment.name());
    } catch (IllegalArgumentException e) {
      return Environment.Known.ENVIRONMENT_BROWSER;
    }
  }

  /**
   * Configures the computer use tool in the LLM request if it is not already configured.
   *
   * @param computerEnvironment The environment to configure the computer use tool for.
   * @param llmRequestBuilder The LLM request builder to add the computer use tool to.
   */
  private static void configureComputerUseIfNeeded(
      LlmRequest.Builder llmRequestBuilder, ComputerEnvironment computerEnvironment) {
    // Get the current config from the LLM request
    GenerateContentConfig config =
        llmRequestBuilder.config().orElse(GenerateContentConfig.builder().build());

    // Check if computer use is already configured
    if (config.tools().orElse(ImmutableList.of()).stream()
        .anyMatch(t -> t.computerUse().isPresent())) {
      logger.debug("Computer use already configured");
      return;
    }

    // Configure the computer
    Environment.Known knownEnv = getEnvironment(computerEnvironment);
    Tool computerUseTool =
        Tool.builder().computerUse(ComputerUse.builder().environment(knownEnv).build()).build();
    // Add the computer use tool to the list of tools in the config
    List<Tool> currentTools = new ArrayList<>(config.tools().orElse(ImmutableList.of()));
    currentTools.add(computerUseTool);
    llmRequestBuilder.config(config.toBuilder().tools(ImmutableList.copyOf(currentTools)).build());
    logger.debug("Added computer use tool with environment: {}", knownEnv);
  }
}
