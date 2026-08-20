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

package com.google.adk.tokt;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.models.Gemini;
import com.google.adk.runner.Runner;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Java-only showcases of {@link KotlinAdkToJava#asJavaRunner}, driven entirely through the ADK Java
 * {@link Runner} API: one adapts a whole ADK Java agent onto the Kotlin engine, the other runs a
 * native Kotlin agent - built from Java via the Kotlin builders - whose tool, toolset, and plugin
 * are adapted ADK Java components. API-key gated, so they skip (rather than fail) when {@code
 * GOOGLE_API_KEY} is unset.
 */
@RunWith(JUnit4.class)
public final class KtRunnerToJavaShowcaseTest {

  @Test
  public void kotlinEngineRunner_drivenThroughTheJavaRunnerApi() {
    String apiKey = System.getenv("GOOGLE_API_KEY");
    assumeTrue(
        "GOOGLE_API_KEY not set; skipping live showcase.", apiKey != null && !apiKey.isEmpty());

    // An ordinary ADK Java agent.
    LlmAgent agent =
        LlmAgent.builder()
            .name("assistant")
            .model(
                new Gemini(
                    "gemini-flash-latest", Client.builder().apiKey(apiKey).vertexAI(false).build()))
            .instruction("Reply with a short greeting.")
            .build();

    // Adapt it onto the Kotlin engine, then expose that Kotlin runner as an ADK Java Runner.
    Runner runner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .agent(JavaAdkToKt.asKtAgent(agent))
                .appName("showcase")
                .build());

    // From here it is just the ADK Java Runner API.
    List<Event> events =
        runner
            .runAsync(
                "user",
                "session",
                Content.builder().role("user").parts(Part.fromText("Hi")).build(),
                RunConfig.builder().build())
            .toList()
            .blockingGet();

    assertThat(events).isNotEmpty();
    assertThat(runner.appName()).isEqualTo("showcase");
  }

  @Test
  public void kotlinAgentWithAdaptedJavaComponents_drivenThroughTheJavaRunnerApi() {
    String apiKey = System.getenv("GOOGLE_API_KEY");
    assumeTrue(
        "GOOGLE_API_KEY not set; skipping live showcase.", apiKey != null && !apiKey.isEmpty());

    // Ordinary ADK Java components: a model, a FunctionTool, a BaseToolset, and a Plugin.
    Gemini javaModel =
        new Gemini("gemini-flash-latest", Client.builder().apiKey(apiKey).vertexAI(false).build());
    LiveInteropTools.MathToolset mathToolset = new LiveInteropTools.MathToolset();
    LiveInteropTools.StateProbePlugin plugin = new LiveInteropTools.StateProbePlugin();

    // A native Kotlin LlmAgent, built from Java via the Kotlin builder, carrying the adapted Java
    // tool and toolset.
    com.google.adk.kt.agents.LlmAgent agent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("assistant")
            .model(JavaAdkToKt.asKtModel(javaModel))
            .instruction("Use the weather and math tools when relevant, then answer briefly.")
            .tools(
                ImmutableList.of(
                    JavaAdkToKt.asKtTool(
                        FunctionTool.create(LiveInteropTools.class, "getWeather"))))
            .toolsets(ImmutableList.of(JavaAdkToKt.asKtToolset(mathToolset)))
            .build();

    // The adapted Java plugin is installed on the Kotlin runner via an App, built from Java too.
    App app =
        App.builder()
            .appName("showcase")
            .rootAgent(agent)
            .plugins(ImmutableList.of(JavaAdkToKt.asKtPlugin(plugin)))
            .build();

    // Expose the Kotlin runner as an ADK Java Runner; from here it is just the ADK Java API.
    Runner runner = KotlinAdkToJava.asJavaRunner(InMemoryRunner.builder().app(app).build());

    List<Event> events =
        runner
            .runAsync(
                "user",
                "session",
                Content.builder()
                    .role("user")
                    .parts(Part.fromText("What's the weather in Paris, and what is 6 + 7?"))
                    .build(),
                RunConfig.builder().maxLlmCalls(8).build())
            .toList()
            .blockingGet();

    assertThat(events).isNotEmpty();

    // The adapted Java tool ran on the Kotlin engine and is visible as a Java-shaped event.
    ImmutableList<String> toolNames =
        events.stream()
            .flatMap(e -> e.functionResponses().stream())
            .map(fr -> fr.name().orElse(""))
            .collect(toImmutableList());
    assertThat(toolNames).contains("getWeather");

    // The adapted Java toolset was provisioned with a live context by the bridge.
    assertThat(mathToolset.provisionedForAgents()).contains("assistant");

    // The adapted Java plugin's after-tool callback ran, and its state write reached a Java event.
    boolean pluginWroteState =
        events.stream().anyMatch(e -> e.actions().stateDelta().containsKey("last_tool"));
    assertThat(pluginWroteState).isTrue();

    // A final natural-language answer came back through the Java API after the tool step.
    assertThat(lastNonBlankText(events)).isNotEmpty();
  }

  private static String lastNonBlankText(List<Event> events) {
    String text = "";
    for (Event event : events) {
      if (event.content().isEmpty()) {
        continue;
      }
      for (Part part : event.content().get().parts().orElse(ImmutableList.of())) {
        String candidate = part.text().orElse("");
        if (!candidate.isBlank()) {
          text = candidate;
        }
      }
    }
    return text;
  }
}
