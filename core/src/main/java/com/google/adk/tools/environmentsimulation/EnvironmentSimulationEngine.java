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

package com.google.adk.tools.environmentsimulation;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decides what answers a tool call that the configuration says should not really run. */
final class EnvironmentSimulationEngine {

  private static final Logger logger = LoggerFactory.getLogger(EnvironmentSimulationEngine.class);

  private final EnvironmentSimulationConfig config;
  private final ImmutableMap<String, ToolSimulationConfig> toolSimulationConfigs;
  private final boolean mocksAnything;
  private final ToolConnectionAnalyzer analyzer;
  private final ToolSpecMockStrategy mockStrategy;
  private final Map<String, Map<Object, Map<String, Object>>> stateStore =
      new ConcurrentHashMap<>();
  private final Random random = new Random();

  /** The one connection analysis, built on the first tool call that needs it. */
  private final AtomicReference<Completable> analysis = new AtomicReference<>();

  private volatile @Nullable ToolConnectionMap toolConnectionMap;

  EnvironmentSimulationEngine(EnvironmentSimulationConfig config) {
    this.config = config;
    this.toolSimulationConfigs =
        config.toolSimulationConfigs().stream()
            .collect(toImmutableMap(ToolSimulationConfig::toolName, identity()));
    this.mocksAnything =
        config.toolSimulationConfigs().stream()
            .anyMatch(
                toolSimulationConfig ->
                    toolSimulationConfig.mockStrategyType()
                        != MockStrategy.MOCK_STRATEGY_UNSPECIFIED);
    this.analyzer =
        new ToolConnectionAnalyzer(config.simulationModel(), config.simulationModelConfiguration());
    this.mockStrategy =
        new ToolSpecMockStrategy(config.simulationModel(), config.simulationModelConfiguration());
  }

  /**
   * Simulates one tool call.
   *
   * @return the response to answer the call with, or empty to let the tool really run.
   */
  Maybe<Map<String, Object>> simulate(
      BaseTool tool, Map<String, Object> args, ToolContext toolContext) {
    ToolSimulationConfig toolSimulationConfig = toolSimulationConfigs.get(tool.name());
    if (toolSimulationConfig == null) {
      return Maybe.empty();
    }
    return analyzeToolConnections(toolContext)
        .andThen(Maybe.defer(() -> inject(toolSimulationConfig, args)))
        .switchIfEmpty(Maybe.defer(() -> mock(tool, toolSimulationConfig, args)));
  }

  /**
   * Waits for the connection analysis, running it if this is the call that has to.
   *
   * <p>The analysis is one model call for the whole simulation, but tool calls arrive concurrently,
   * so the work is held as a shared cold {@link Completable}: whichever call gets there first
   * publishes it, the rest subscribe to that same one, and only one of them ever reaches the model.
   * Reading a flag here and setting it when the analysis finished would instead let every call that
   * arrived while the first was still running start its own, and the last to finish would decide
   * the connection map.
   *
   * <p>A configuration that mocks nothing has no use for a connection map, and skips the analysis.
   */
  private Completable analyzeToolConnections(ToolContext toolContext) {
    if (!mocksAnything) {
      return Completable.complete();
    }
    return Completable.defer(
        () ->
            analysis.updateAndGet(
                started -> started == null ? analyze(toolContext).cache() : started));
  }

  private Completable analyze(ToolContext toolContext) {
    if (!(toolContext.invocationContext().agent() instanceof LlmAgent agent)) {
      return Completable.complete();
    }
    return agent
        .canonicalTools(toolContext)
        .toList()
        .flatMap(analyzer::analyze)
        .doOnSuccess(connectionMap -> toolConnectionMap = connectionMap)
        .ignoreElement()
        // A simulation is more useful with unconnected mock responses than with none at all, and
        // the result is cached, so a failure here must not fail every later tool call too.
        .doOnError(
            throwable ->
                logger.warn(
                    "Tool connection analysis failed. Proceeding without a connection map.",
                    throwable))
        .onErrorComplete();
  }

  /** Answers with the first injection whose arguments match and whose probability comes up. */
  private Maybe<Map<String, Object>> inject(
      ToolSimulationConfig toolSimulationConfig, Map<String, Object> args) {
    for (InjectionConfig injection : toolSimulationConfig.injectionConfigs()) {
      if (!args.entrySet().containsAll(injection.matchArgs().entrySet())) {
        continue;
      }
      if (!injects(injection)) {
        continue;
      }
      Maybe<Map<String, Object>> response =
          Maybe.just(
              injection
                  .injectedError()
                  .<Map<String, Object>>map(
                      error ->
                          ImmutableMap.of(
                              "error_code",
                              error.injectedHttpErrorCode(),
                              "error_message",
                              error.errorMessage()))
                  .orElseGet(injection::injectedResponse));
      double latencySeconds = injection.injectedLatencySeconds();
      return latencySeconds > 0
          ? response.delay((long) (latencySeconds * 1000), MILLISECONDS)
          : response;
    }
    return Maybe.empty();
  }

  /** Seeding and drawing have to be one step, or a concurrent call draws off somebody's seed. */
  private synchronized boolean injects(InjectionConfig injection) {
    injection.randomSeed().ifPresent(random::setSeed);
    return random.nextDouble() < injection.injectionProbability();
  }

  private Maybe<Map<String, Object>> mock(
      BaseTool tool, ToolSimulationConfig toolSimulationConfig, Map<String, Object> args) {
    if (toolSimulationConfig.mockStrategyType() == MockStrategy.MOCK_STRATEGY_UNSPECIFIED) {
      logger.warn(
          "Tool '{}' did not hit any injection config and has no mock strategy configured."
              + " Returning no-op.",
          tool.name());
      return Maybe.empty();
    }
    return mockStrategy
        .mock(tool, args, toolConnectionMap, stateStore, config.environmentData(), config.tracing())
        .toMaybe();
  }
}
