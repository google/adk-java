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
package com.google.adk.models;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.FakeGeminiApi.Kind;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Runs agents on {@link Gemini} with context caching, against a fake Gemini API. */
@RunWith(JUnit4.class)
public final class GeminiContextCachingTest {

  private static final String MODEL = "gemini-test-model";
  private static final String INSTRUCTION = "Answer every question briefly and politely.";
  private static final ContextCacheConfig CACHE_CONFIG =
      new ContextCacheConfig(/* cacheIntervals= */ 10, Duration.ofMinutes(30), /* minTokens= */ 0);
  private static final String USER_ID = "user";
  private static final String CACHE_NAME = "cachedContents/greeting";
  private static final String FIRST_ANSWER = "Hi, how can I help?";
  private static final RunConfig STREAMING =
      RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build();

  private final FakeGeminiApi api = new FakeGeminiApi();

  @Test
  public void runAsync_secondTurn_sendsCacheNameAndOnlyTheNewMessage() {
    api.queueAnswers(FIRST_ANSWER);
    api.queueCacheNames(CACHE_NAME);
    Runner runner = runner(CACHE_CONFIG);
    String sessionId = newSession(runner);
    run(runner, sessionId, "Hello");

    run(runner, sessionId, "Tell me more");

    // The cache holds the first turn ("Hello" and the first answer), so the second call names the
    // cache and sends only the new message.
    assertThat(texts(api.bodies(Kind.CREATE_CACHE).get(0)))
        .containsExactly("Hello", FIRST_ANSWER)
        .inOrder();
    JsonNode secondCall = lastGenerateBody();
    assertThat(secondCall.path("cachedContent").asText()).isEqualTo(CACHE_NAME);
    assertThat(texts(secondCall)).containsExactly("Tell me more");
  }

  @Test
  public void runAsync_thirdTurn_reusesCacheWithoutCreatingAnother() {
    api.queueCacheNames(CACHE_NAME);
    Runner runner = runner(CACHE_CONFIG);
    String sessionId = newSession(runner);
    run(runner, sessionId, "Hello");
    run(runner, sessionId, "Tell me more");

    run(runner, sessionId, "And then?");

    assertThat(api.bodies(Kind.CREATE_CACHE)).hasSize(1);
    assertThat(lastGenerateBody().path("cachedContent").asText()).isEqualTo(CACHE_NAME);
  }

  @Test
  public void runAsync_streaming_putsCacheMetadataOnlyOnTheFinalEvent() {
    api.queueCacheNames(CACHE_NAME);
    Runner runner = runner(CACHE_CONFIG);
    String sessionId = newSession(runner);
    run(runner, sessionId, "Hello", STREAMING);

    ImmutableList<Event> events = run(runner, sessionId, "Tell me more", STREAMING);

    assertThat(partialEvents(events)).isNotEmpty();
    assertThat(eventsWithCacheMetadata(events)).containsExactly(getLast(events));
  }

  @Test
  public void runAsync_turnThatCreatesCache_staysOnTheCallerThread() {
    api.delayCacheCalls(Duration.ofMillis(300));
    Runner runner = runner(CACHE_CONFIG);
    String sessionId = newSession(runner);
    ImmutableList<String> firstTurn =
        eventThreads(runner, sessionId, "Hello", RunConfig.builder().build());

    ImmutableList<String> secondTurn =
        eventThreads(runner, sessionId, "Tell me more", RunConfig.builder().build());

    assertThat(api.kinds()).contains(Kind.CREATE_CACHE);
    assertThat(firstTurn).isNotEmpty();
    assertThat(secondTurn).isEqualTo(firstTurn);
  }

  @Test
  public void runAsync_streamingTurnThatCreatesCache_staysOnTheCallerThread() {
    api.delayCacheCalls(Duration.ofMillis(300));
    Runner runner = runner(CACHE_CONFIG);
    String sessionId = newSession(runner);
    ImmutableList<String> firstTurn = eventThreads(runner, sessionId, "Hello", STREAMING);

    ImmutableList<String> secondTurn = eventThreads(runner, sessionId, "Tell me more", STREAMING);

    assertThat(api.kinds()).contains(Kind.CREATE_CACHE);
    assertThat(firstTurn).isNotEmpty();
    assertThat(secondTurn).isEqualTo(firstTurn);
  }

  @Test
  public void runAsync_withoutCacheConfig_sendsWholeHistoryWithoutCache() {
    api.queueAnswers(FIRST_ANSWER);
    Runner runner = runner(/* cacheConfig= */ null);
    String sessionId = newSession(runner);
    run(runner, sessionId, "Hello");

    run(runner, sessionId, "Tell me more");

    assertThat(api.kinds()).doesNotContain(Kind.CREATE_CACHE);
    JsonNode secondCall = lastGenerateBody();
    assertThat(secondCall.has("cachedContent")).isFalse();
    assertThat(texts(secondCall)).containsExactly("Hello", FIRST_ANSWER, "Tell me more").inOrder();
  }

  private Runner runner(@Nullable ContextCacheConfig cacheConfig) {
    LlmAgent agent =
        LlmAgent.builder()
            .name("assistant")
            .model(new Gemini(MODEL, api.client()))
            .instruction(INSTRUCTION)
            .build();
    App.Builder app = App.builder().name("cache_app").rootAgent(agent);
    if (cacheConfig != null) {
      app.contextCacheConfig(cacheConfig);
    }
    return Runner.builder()
        .app(app.build())
        .sessionService(new InMemorySessionService())
        .artifactService(new InMemoryArtifactService())
        .build();
  }

  private static String newSession(Runner runner) {
    return runner.sessionService().createSession(runner.appName(), USER_ID).blockingGet().id();
  }

  /** Returns the request body of the latest model call the fake served. */
  private JsonNode lastGenerateBody() {
    return getLast(api.bodies(Kind.GENERATE));
  }

  /** Returns the events that are partial (streamed chunks). */
  private static ImmutableList<Event> partialEvents(ImmutableList<Event> events) {
    return events.stream()
        .filter(event -> event.partial().orElse(false))
        .collect(toImmutableList());
  }

  /** Returns the events that carry cache metadata. */
  private static ImmutableList<Event> eventsWithCacheMetadata(ImmutableList<Event> events) {
    return events.stream()
        .filter(event -> event.cacheMetadata().isPresent())
        .collect(toImmutableList());
  }

  /** Returns the name of the thread each event of one turn was emitted on. */
  private static ImmutableList<String> eventThreads(
      Runner runner, String sessionId, String text, RunConfig runConfig) {
    return ImmutableList.copyOf(
        runner
            .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(text)), runConfig)
            .map(event -> Thread.currentThread().getName())
            .toList()
            .blockingGet());
  }

  @CanIgnoreReturnValue
  private static ImmutableList<Event> run(Runner runner, String sessionId, String text) {
    return run(runner, sessionId, text, RunConfig.builder().build());
  }

  @CanIgnoreReturnValue
  private static ImmutableList<Event> run(
      Runner runner, String sessionId, String text, RunConfig runConfig) {
    return ImmutableList.copyOf(
        runner
            .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(text)), runConfig)
            .toList()
            .blockingGet());
  }

  /** Returns the text of every part of every content in a request body, in order. */
  private static ImmutableList<String> texts(JsonNode body) {
    return stream(body.get("contents"))
        .flatMap(content -> stream(content.get("parts")))
        .map(part -> part.path("text").asText())
        .collect(toImmutableList());
  }
}
