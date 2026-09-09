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

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.models.Gemini;
import com.google.adk.runner.Runner;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Java-only showcase of {@link KotlinAdkToJava#asJavaRunner}, driven through the ADK Java {@link
 * Runner} API: it runs a native Kotlin agent - built from Java via the Kotlin builders - whose
 * model and tool are adapted ADK Java components. API-key gated, so it skips (rather than fails)
 * when {@code GOOGLE_API_KEY} is unset.
 */
@RunWith(JUnit4.class)
public final class KtRunnerToJavaShowcaseTest {

  @Test
  public void kotlinAgentWithAdaptedJavaComponents_drivenThroughTheJavaRunnerApi() {
    String apiKey = System.getenv("GOOGLE_API_KEY");
    assumeTrue(
        "GOOGLE_API_KEY not set; skipping live showcase.", apiKey != null && !apiKey.isEmpty());

    // Ordinary ADK Java components adapted onto the engine: a model and a FunctionTool.
    Gemini javaModel =
        new Gemini("gemini-flash-latest", Client.builder().apiKey(apiKey).vertexAI(false).build());
    LlmAgent agent =
        LlmAgent.builder()
            .name("assistant")
            .model(JavaAdkToKt.asKtModel(javaModel))
            .instruction("Use the weather tool, then answer briefly.")
            .tools(
                ImmutableList.of(
                    JavaAdkToKt.asKtTool(
                        FunctionTool.create(LiveInteropTools.class, "getWeather"))))
            .build();
    App app = App.builder().appName("showcase").rootAgent(agent).build();

    // Expose the Kotlin runner as an ADK Java Runner; from here it is just the ADK Java API.
    Runner runner = KotlinAdkToJava.asJavaRunner(InMemoryRunner.builder().app(app).build());

    List<Event> events =
        runner
            .runAsync(
                "user",
                "session",
                Content.builder()
                    .role("user")
                    .parts(Part.fromText("What's the weather in Paris?"))
                    .build(),
                RunConfig.builder().maxLlmCalls(8).autoCreateSession(true).build())
            .toList()
            .blockingGet();

    assertThat(events).isNotEmpty();

    // The adapted Java tool ran on the Kotlin engine and surfaced as a Java-shaped event.
    ImmutableList<String> toolNames =
        events.stream()
            .flatMap(e -> e.functionResponses().stream())
            .map(fr -> fr.name().orElse(""))
            .collect(toImmutableList());
    assertThat(toolNames).contains("getWeather");

    // finishReason (STOP) and non-zero token usage survived EventCodec's Java conversion.
    Event finalModelEvent =
        events.stream()
            .filter(e -> e.finishReason().isPresent())
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no event carried a finish reason"));
    assertThat(finalModelEvent.finishReason()).hasValue(new FinishReason(FinishReason.Known.STOP));
    assertThat(
            events.stream()
                .anyMatch(
                    e ->
                        e.usageMetadata()
                            .map(u -> u.totalTokenCount().orElse(0) > 0)
                            .orElse(false)))
        .isTrue();
  }
}
