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
package com.example.contextcaching;

import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.models.CacheMetadata;
import com.google.adk.runner.Runner;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Runs several turns of one session against {@link ContextCachingAgent} and prints what the cache
 * does on each turn. It pauses once so the cache expires, and the next turn creates a new one.
 * Exact turn-by-turn output depends on model latency, since a slow turn can outlast the TTL.
 */
public final class ContextCachingRun {

  private static final ImmutableList<String> PROMPTS =
      ImmutableList.of(
          "In one sentence, what should I do during a thunderstorm?",
          "How is wind chill defined, and when does it apply?",
          "What units does the reference use for wind speed?",
          "What is the average annual rainfall listed for Verdant?",
          "Which named city is the driest?",
          "What is the July average temperature for Puerto Brisa?");

  /** Zero-based turn after which the demo waits for the cache to expire. */
  private static final int EXPIRY_AFTER_TURN = 2;

  private static final String USER_ID = "demo-user";

  private ContextCachingRun() {}

  public static void main(String[] args) throws InterruptedException {
    String model = args.length > 0 ? args[0] : ContextCachingAgent.DEFAULT_MODEL;
    App app = ContextCachingAgent.createApp(model);
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();

    System.out.printf(
        "Context caching demo on %s, cache TTL %ds.%n",
        model, ContextCachingAgent.CACHE_TTL.toSeconds());

    String activeCacheName = null;
    for (int turn = 0; turn < PROMPTS.size(); turn++) {
      String prompt = PROMPTS.get(turn);
      System.out.printf("%n===== Turn %d: %s =====%n", turn + 1, prompt);
      Event answer =
          runner
              .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(prompt)))
              .blockingLast();
      System.out.println("assistant> " + answer.stringifyContent().strip());

      CacheMetadata cache = answer.cacheMetadata().orElse(null);
      if (cache != null) {
        System.out.printf(
            "  cache: %s | cachedContents=%d invocationsUsed=%d%n",
            describe(cache, activeCacheName),
            cache.contentsCount(),
            cache.invocationsUsed().orElse(0));
        activeCacheName = cache.cacheName().orElse(activeCacheName);
      }
      answer
          .usageMetadata()
          .ifPresent(
              usage ->
                  System.out.printf(
                      "  prompt tokens: %d, served from cache: %d%n",
                      usage.promptTokenCount().orElse(0),
                      usage.cachedContentTokenCount().orElse(0)));

      if (turn == EXPIRY_AFTER_TURN) {
        Duration wait = ContextCachingAgent.CACHE_TTL.plusSeconds(2);
        System.out.printf("%n... waiting %ds so the cache expires ...%n", wait.toSeconds());
        Thread.sleep(wait.toMillis());
      }
    }
  }

  /** Describes a turn's cache relative to the cache the previous turns used. */
  private static String describe(CacheMetadata cache, @Nullable String previousCacheName) {
    String name = cache.cacheName().orElse(null);
    if (name == null) {
      return previousCacheName == null
          ? "no cache yet (fingerprint only)"
          : "no active cache (fingerprint only)";
    }
    if (previousCacheName == null) {
      return "CREATED " + name;
    }
    return name.equals(previousCacheName) ? "REUSED " + name : "RE-CREATED " + name;
  }
}
