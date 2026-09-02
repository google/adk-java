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

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EnvironmentSimulationEngineTest {

  /** A phrase that appears only in the prompt the connection analysis sends. */
  private static final String ANALYSIS_PROMPT = "expert software architect";

  /** A phrase that appears only in the prompt a mocked tool response is generated from. */
  private static final String MOCK_PROMPT = "stateful tool simulator";

  private static final String MOCK_RESPONSE_JSON = "{\"status\": \"ok\"}";

  // The registry caches one model instance per name, so each test gets its own name and finds its
  // own fake here rather than the one a test that ran earlier left behind.
  private static final ConcurrentMap<String, TestLlm> SIMULATION_MODELS = new ConcurrentHashMap<>();

  @BeforeClass
  public static void registerSimulationModels() {
    LlmRegistry.registerLlm("test-sim-.*", SIMULATION_MODELS::get);
  }

  @Rule public final TestName testName = new TestName();

  private String modelName;

  @Before
  public void setUp() {
    modelName = "test-sim-" + testName.getMethodName();
  }

  @Test
  public void simulate_toolNotInTheConfiguration_letsTheToolRun() {
    StubTool calendar = new StubTool("calendar");
    EnvironmentSimulationEngine engine = new EnvironmentSimulationEngine(mockingConfig("weather"));

    Map<String, Object> answer =
        engine.simulate(calendar, ImmutableMap.of(), toolContextFor(calendar)).blockingGet();

    assertThat(answer).isNull();
  }

  @Test
  public void simulate_injectedError_answersWithTheError() {
    StubTool weather = new StubTool("weather");
    EnvironmentSimulationEngine engine =
        new EnvironmentSimulationEngine(
            configOf(
                ToolSimulationConfig.builder()
                    .toolName("weather")
                    .injectionConfigs(
                        ImmutableList.of(
                            InjectionConfig.builder()
                                .injectedError(
                                    InjectedError.builder()
                                        .injectedHttpErrorCode(503)
                                        .errorMessage("upstream is down")
                                        .build())
                                .build()))
                    .build()));

    Map<String, Object> answer =
        engine
            .simulate(weather, ImmutableMap.of("city", "Zurich"), toolContextFor(weather))
            .blockingGet();

    assertThat(answer).containsExactly("error_code", 503, "error_message", "upstream is down");
  }

  @Test
  public void simulate_matchArgsAreASubsetOfTheCall_injects() {
    StubTool weather = new StubTool("weather");
    EnvironmentSimulationEngine engine =
        new EnvironmentSimulationEngine(matchingConfig(ImmutableMap.of("city", "Zurich")));

    Map<String, Object> answer =
        engine
            .simulate(
                weather,
                ImmutableMap.of("city", "Zurich", "units", "celsius"),
                toolContextFor(weather))
            .blockingGet();

    assertThat(answer).containsExactly("temperature", 20);
  }

  @Test
  public void simulate_matchArgsDoNotMatchTheCall_letsTheToolRun() {
    StubTool weather = new StubTool("weather");
    EnvironmentSimulationEngine engine =
        new EnvironmentSimulationEngine(matchingConfig(ImmutableMap.of("city", "Zurich")));

    Map<String, Object> answer =
        engine
            .simulate(weather, ImmutableMap.of("city", "Geneva"), toolContextFor(weather))
            .blockingGet();

    assertThat(answer).isNull();
  }

  @Test
  public void simulate_injectionProbabilityIsZero_letsTheToolRun() {
    StubTool weather = new StubTool("weather");
    EnvironmentSimulationEngine engine =
        new EnvironmentSimulationEngine(
            configOf(
                ToolSimulationConfig.builder()
                    .toolName("weather")
                    .injectionConfigs(
                        ImmutableList.of(
                            InjectionConfig.builder()
                                .injectionProbability(0.0)
                                .injectedResponse(ImmutableMap.of("temperature", 20))
                                .build()))
                    .build()));

    Map<String, Object> answer =
        engine.simulate(weather, ImmutableMap.of(), toolContextFor(weather)).blockingGet();

    assertThat(answer).isNull();
  }

  @Test
  public void simulate_seededInjection_drawsTheSameWayEveryRun() {
    StubTool weather = new StubTool("weather");
    ToolContext toolContext = toolContextFor(weather);
    EnvironmentSimulationConfig config =
        configOf(
            ToolSimulationConfig.builder()
                .toolName("weather")
                .injectionConfigs(
                    ImmutableList.of(
                        InjectionConfig.builder()
                            .randomSeed(42L)
                            .injectionProbability(0.5)
                            .injectedResponse(ImmutableMap.of("temperature", 20))
                            .build()))
                .build());

    // Every call re-seeds before it draws, so all of them decide the same way. An engine that
    // dropped the seed would draw around this threshold roughly half the time instead.
    boolean expectedToInject = new Random(42L).nextDouble() < 0.5;
    EnvironmentSimulationEngine engine = new EnvironmentSimulationEngine(config);
    for (int call = 0; call < 10; call++) {
      Map<String, Object> answer =
          engine.simulate(weather, ImmutableMap.of(), toolContext).blockingGet();

      assertThat(answer != null).isEqualTo(expectedToInject);
    }
  }

  @Test
  public void simulate_mockStrategy_answersWithWhatTheModelWrote() {
    answerWith(MOCK_RESPONSE_JSON);
    StubTool weather = new StubTool("weather");
    EnvironmentSimulationEngine engine = new EnvironmentSimulationEngine(mockingConfig("weather"));

    Map<String, Object> answer =
        engine.simulate(weather, ImmutableMap.of(), toolContextFor(weather)).blockingGet();

    assertThat(answer).containsExactly("status", "ok");
  }

  @Test
  public void simulate_repeatedCalls_analyzeToolConnectionsOnce() {
    answerWith(MOCK_RESPONSE_JSON);
    StubTool weather = new StubTool("weather");
    ToolContext toolContext = toolContextFor(weather);
    EnvironmentSimulationEngine engine = new EnvironmentSimulationEngine(mockingConfig("weather"));

    for (int call = 0; call < 3; call++) {
      Map<String, Object> unused =
          engine.simulate(weather, ImmutableMap.of(), toolContext).blockingGet();
    }

    assertThat(promptsContaining(ANALYSIS_PROMPT)).isEqualTo(1);
    assertThat(promptsContaining(MOCK_PROMPT)).isEqualTo(3);
  }

  @Test
  public void simulate_concurrentCalls_analyzeToolConnectionsOnce() throws Exception {
    // The analysis is slow enough that every caller is inside simulate() while the first one is
    // still waiting on the model. Reading a flag and setting it once the analysis returned would
    // let each of them start an analysis of its own and overwrite the others' connection map.
    answerAfter(MOCK_RESPONSE_JSON, Duration.ofMillis(300));
    StubTool weather = new StubTool("weather");
    ToolContext toolContext = toolContextFor(weather);
    EnvironmentSimulationEngine engine = new EnvironmentSimulationEngine(mockingConfig("weather"));

    int callers = 8;
    CyclicBarrier allReady = new CyclicBarrier(callers);
    ExecutorService callerPool = Executors.newFixedThreadPool(callers);
    List<Future<Map<String, Object>>> answers = new ArrayList<>();
    try {
      for (int caller = 0; caller < callers; caller++) {
        answers.add(
            callerPool.submit(
                () -> {
                  int unused = allReady.await();
                  return engine.simulate(weather, ImmutableMap.of(), toolContext).blockingGet();
                }));
      }
      for (Future<Map<String, Object>> answer : answers) {
        assertThat(answer.get(60, SECONDS)).containsExactly("status", "ok");
      }
    } finally {
      callerPool.shutdownNow();
    }

    assertThat(promptsContaining(ANALYSIS_PROMPT)).isEqualTo(1);
    assertThat(promptsContaining(MOCK_PROMPT)).isEqualTo(callers);
  }

  @Test
  public void simulate_toolConnectionAnalysisFails_stillAnswersWithAMock() {
    // The first model call is the analysis and it fails; the second is the mock response.
    registerSimulationModel(
        createTestLlm(
            Flowable.<LlmResponse>error(new IllegalStateException("model unavailable")),
            Flowable.just(createTextLlmResponse(MOCK_RESPONSE_JSON))));
    StubTool weather = new StubTool("weather");
    EnvironmentSimulationEngine engine = new EnvironmentSimulationEngine(mockingConfig("weather"));

    Map<String, Object> answer =
        engine.simulate(weather, ImmutableMap.of(), toolContextFor(weather)).blockingGet();

    assertThat(answer).containsExactly("status", "ok");
  }

  private EnvironmentSimulationConfig configOf(ToolSimulationConfig... toolSimulationConfigs) {
    return EnvironmentSimulationConfig.builder()
        .simulationModel(modelName)
        .toolSimulationConfigs(ImmutableList.copyOf(toolSimulationConfigs))
        .build();
  }

  private EnvironmentSimulationConfig mockingConfig(String toolName) {
    return configOf(
        ToolSimulationConfig.builder()
            .toolName(toolName)
            .mockStrategyType(MockStrategy.MOCK_STRATEGY_TOOL_SPEC)
            .build());
  }

  private EnvironmentSimulationConfig matchingConfig(Map<String, Object> matchArgs) {
    return configOf(
        ToolSimulationConfig.builder()
            .toolName("weather")
            .injectionConfigs(
                ImmutableList.of(
                    InjectionConfig.builder()
                        .matchArgs(matchArgs)
                        .injectedResponse(ImmutableMap.of("temperature", 20))
                        .build()))
            .build());
  }

  private ToolContext toolContextFor(BaseTool... tools) {
    LlmAgent agent =
        createTestAgentBuilder(new TestLlm(ImmutableList.of()))
            .tools(ImmutableList.copyOf(tools))
            .build();
    return ToolContext.builder(createInvocationContext(agent)).build();
  }

  /** Points this test's simulation model at a fake that always answers with {@code json}. */
  private void answerWith(String json) {
    registerSimulationModel(createTestLlm(() -> Flowable.just(createTextLlmResponse(json))));
  }

  /** The same, but the fake takes {@code latency} to answer. */
  private void answerAfter(String json, Duration latency) {
    registerSimulationModel(
        createTestLlm(
            () ->
                Flowable.just(createTextLlmResponse(json))
                    .delay(latency.toMillis(), MILLISECONDS)));
  }

  private void registerSimulationModel(TestLlm simulationModel) {
    SIMULATION_MODELS.put(modelName, simulationModel);
  }

  /** How many prompts the simulation sent to the model that contain {@code phrase}. */
  private long promptsContaining(String phrase) {
    return SIMULATION_MODELS.get(modelName).getRequests().stream()
        .map(
            request ->
                request.contents().stream()
                    .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
                    .map(part -> part.text().orElse(""))
                    .collect(joining()))
        .filter(prompt -> prompt.contains(phrase))
        .count();
  }

  /** A tool that is never really called, and only has to have a name and a declaration. */
  private static final class StubTool extends BaseTool {

    StubTool(String name) {
      super(name, "the " + name + " tool");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder().name(name()).description(description()).build());
    }
  }
}
