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
import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.models.FakeGeminiApi.Kind;
import com.google.adk.models.GeminiContextCacheManager.CacheResult;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GeminiContextCacheManager}. */
@RunWith(JUnit4.class)
public final class GeminiContextCacheManagerTest {

  private static final String MODEL = "gemini-test-model";
  private static final String INSTRUCTION = "Answer every question briefly and politely.";
  private static final Content USER_1 = text("user", "What is the capital of France?");
  private static final Content MODEL_1 = text("model", "Paris.");
  private static final Content USER_2 = text("user", "And of Spain?");
  private static final ContextCacheConfig CACHE_CONFIG =
      new ContextCacheConfig(10, Duration.ofMinutes(30), 0);
  private static final GenerateContentConfig CONFIG =
      GenerateContentConfig.builder()
          .systemInstruction(Content.fromParts(Part.fromText(INSTRUCTION)))
          .tools(functionTool("get_weather", "get_time"))
          .toolConfig(
              ToolConfig.builder()
                  .functionCallingConfig(
                      FunctionCallingConfig.builder().mode(FunctionCallingConfigMode.Known.AUTO))
                  .build())
          .temperature(0.2f)
          .build();
  private static final String EXISTING_CACHE = "cachedContents/existing";
  private static final Tool SEARCH_TOOL =
      Tool.builder().googleSearch(GoogleSearch.builder().build()).build();
  private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

  private final FakeGeminiApi api = new FakeGeminiApi();
  private final GeminiContextCacheManager manager =
      new GeminiContextCacheManager(api.client(), MODEL, InstantSource.fixed(NOW));

  @After
  public void clearInterruptFlag() {
    // The interrupt tests set the flag; a failed assertion must not leak it into later tests.
    boolean unused = Thread.interrupted();
  }

  @Test
  public void handleContextCaching_withoutCacheConfig_fails() {
    LlmRequest request =
        LlmRequest.builder().model(MODEL).contents(ImmutableList.of(USER_1)).build();

    assertThrows(IllegalArgumentException.class, () -> handle(request));
  }

  @Test
  public void handleContextCaching_withoutPreviousMetadata_onlyFingerprintsThePrefix() {
    LlmRequest request = request(USER_1, MODEL_1, USER_2).build();

    CacheResult result = handle(request);

    assertThat(result.request()).isEqualTo(request);
    assertThat(result.metadata().contentsCount()).isEqualTo(2);
    assertThat(result.metadata().fingerprint()).matches("[0-9a-f]{16}");
    assertThat(result.metadata().cacheName()).isEmpty();
    assertThat(api.kinds()).isEmpty();
  }

  @Test
  public void handleContextCaching_upperCaseUserRole_isNotCached() {
    LlmRequest request = request(USER_1, MODEL_1, text("USER", "And of Spain?")).build();

    assertThat(handle(request).metadata().contentsCount()).isEqualTo(2);
  }

  @Test
  public void handleContextCaching_validCache_reusesItWithoutCallingTheApi() {
    LlmRequest request = request(USER_1, MODEL_1, USER_2).build();
    CacheMetadata cache = activeCacheFor(request, inOneHour(), /* invocationsUsed= */ 3);

    CacheResult result = handle(request.toBuilder().cacheMetadata(cache).build());

    assertThat(result.metadata()).isEqualTo(cache);
    GenerateContentConfig config = result.request().config().get();
    assertThat(config.cachedContent()).hasValue(EXISTING_CACHE);
    assertThat(config.systemInstruction()).isEmpty();
    assertThat(config.tools()).isEmpty();
    assertThat(config.toolConfig()).isEmpty();
    assertThat(config.temperature()).hasValue(0.2f);
    assertThat(result.request().contents()).containsExactly(USER_2);
    assertThat(api.kinds()).isEmpty();
  }

  @Test
  public void handleContextCaching_cacheUsedForCacheIntervals_isStillReused() {
    LlmRequest request = request(USER_1, MODEL_1, USER_2).build();
    CacheMetadata cache = activeCacheFor(request, inOneHour(), CACHE_CONFIG.cacheIntervals());

    CacheResult result = handle(request.toBuilder().cacheMetadata(cache).build());

    assertThat(result.metadata()).isEqualTo(cache);
    assertThat(api.kinds()).isEmpty();
  }

  @Test
  public void handleContextCaching_cacheUsedForMoreThanCacheIntervals_isReplaced() {
    LlmRequest request = request(USER_1, MODEL_1, USER_2).cacheableContentsTokenCount(5000).build();
    CacheMetadata cache = activeCacheFor(request, inOneHour(), CACHE_CONFIG.cacheIntervals() + 1);

    CacheResult result = handle(request.toBuilder().cacheMetadata(cache).build());

    assertThat(api.kinds()).containsExactly(Kind.DELETE_CACHE, Kind.CREATE_CACHE).inOrder();
    assertThat(result.metadata().cacheName()).hasValue("cachedContents/cache-1");
    assertThat(result.metadata().invocationsUsed()).hasValue(1);
  }

  @Test
  public void handleContextCaching_expiredCache_isDeletedAndReplaced() {
    LlmRequest request = request(USER_1, MODEL_1, USER_2).cacheableContentsTokenCount(5000).build();
    CacheMetadata cache = activeCacheFor(request, NOW.minusSeconds(1), /* invocationsUsed= */ 2);

    CacheResult result = handle(request.toBuilder().cacheMetadata(cache).build());

    assertThat(api.kinds()).containsExactly(Kind.DELETE_CACHE, Kind.CREATE_CACHE).inOrder();
    assertThat(api.paths(Kind.DELETE_CACHE).get(0)).endsWith("/" + EXISTING_CACHE);
    assertThat(result.metadata().cacheName()).hasValue("cachedContents/cache-1");
    assertThat(result.metadata().contentsCount()).isEqualTo(2);
    assertThat(result.metadata().invocationsUsed()).hasValue(1);
    assertThat(result.request().config().get().cachedContent()).hasValue("cachedContents/cache-1");
    assertThat(result.request().contents()).containsExactly(USER_2);
  }

  @Test
  public void handleContextCaching_changedInstruction_deletesCacheAndStartsNewFingerprint() {
    LlmRequest original =
        request(USER_1, MODEL_1, USER_2).cacheableContentsTokenCount(5000).build();
    CacheMetadata cache = activeCacheFor(original, inOneHour(), /* invocationsUsed= */ 2);
    LlmRequest changed =
        original.toBuilder()
            .config(
                CONFIG.toBuilder()
                    .systemInstruction(Content.fromParts(Part.fromText("Answer in French.")))
                    .build())
            .cacheMetadata(cache)
            .build();

    CacheResult result = handle(changed);

    assertThat(api.kinds()).containsExactly(Kind.DELETE_CACHE);
    assertThat(result.request()).isEqualTo(changed);
    assertThat(result.metadata().cacheName()).isEmpty();
    assertThat(result.metadata().contentsCount()).isEqualTo(2);
    assertThat(result.metadata().fingerprint()).isNotEqualTo(cache.fingerprint());
  }

  @Test
  public void handleContextCaching_createHttpOptions_areSentWithTheCreateCall() {
    ContextCacheConfig withHeader =
        new ContextCacheConfig(
            10,
            Duration.ofMinutes(30),
            0,
            HttpOptions.builder().headers(ImmutableMap.of("x-adk-test", "create")).build());
    CacheMetadata firstTurn = handle(request(USER_1).cacheConfig(withHeader).build()).metadata();

    var unused =
        handle(
            request(USER_1, MODEL_1, USER_2)
                .cacheConfig(withHeader)
                .cacheMetadata(firstTurn)
                .cacheableContentsTokenCount(5000)
                .build());

    assertThat(api.headers(Kind.CREATE_CACHE).get(0).get("x-adk-test")).isEqualTo("create");
  }

  @Test
  public void handleContextCaching_unchangedPrefix_cachesTheGrownPrefix() throws Exception {
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, USER_2)
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(5000)
            .build();

    CacheResult result = handle(request);

    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE);
    JsonNode create = api.bodies(Kind.CREATE_CACHE).get(0);
    assertThat(create.get("model").asText()).endsWith(MODEL);
    assertThat(texts(create.get("contents")))
        .containsExactlyElementsIn(texts(USER_1, MODEL_1))
        .inOrder();
    assertThat(create.get("systemInstruction").toString()).contains(INSTRUCTION);
    assertThat(create.get("tools").toString()).contains("get_weather");
    assertThat(create.get("toolConfig").toString()).contains("AUTO");
    assertThat(create.get("ttl").asText()).isEqualTo("1800s");
    assertThat(create.get("displayName").asText())
        .isEqualTo("adk-cache-" + NOW.getEpochSecond() + "-2contents");
    CacheMetadata created = result.metadata();
    assertThat(created.cacheName()).hasValue("cachedContents/cache-1");
    assertThat(created.contentsCount()).isEqualTo(2);
    assertThat(created.invocationsUsed()).hasValue(1);
    assertThat(created.expireTime()).hasValue(api.expireTime(1));
    assertThat(result.request().contents()).containsExactly(USER_2);
    // The created fingerprint validates the same request, so the next invocation reuses it.
    assertThat(handle(request.toBuilder().cacheMetadata(created).build()).metadata())
        .isEqualTo(created);
    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE);
  }

  @Test
  public void handleContextCaching_withoutPreviousTokenCount_keepsGrownFingerprint() {
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request = request(USER_1, MODEL_1, USER_2).cacheMetadata(firstTurn).build();

    CacheResult result = handle(request);

    assertThat(api.kinds()).isEmpty();
    assertThat(result.request()).isEqualTo(request);
    assertThat(result.metadata().cacheName()).isEmpty();
    assertThat(result.metadata().contentsCount()).isEqualTo(2);
    assertThat(result.metadata().fingerprint()).isNotEqualTo(firstTurn.fingerprint());
  }

  @Test
  public void handleContextCaching_previousRequestBelowMinTokens_doesNotCreateCache() {
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, USER_2)
            .cacheConfig(new ContextCacheConfig(10, Duration.ofMinutes(30), 6000))
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(5000)
            .build();

    CacheResult result = handle(request);

    assertThat(api.kinds()).isEmpty();
    assertThat(result.metadata().cacheName()).isEmpty();
    assertThat(result.metadata().contentsCount()).isEqualTo(2);
  }

  @Test
  public void handleContextCaching_gemini25PrefixAboveMinimum_createsCache() {
    assertThat(createsCache("gemini-2.5-flash", /* previousTokenCount= */ 3000)).isTrue();
  }

  @Test
  public void handleContextCaching_gemini25PrefixBelowMinimum_doesNotCreateCache() {
    assertThat(createsCache("gemini-2.5-flash", /* previousTokenCount= */ 2000)).isFalse();
  }

  @Test
  public void handleContextCaching_gemini3PrefixBelowMinimum_doesNotCreateCache() {
    assertThat(createsCache("gemini-3-pro", /* previousTokenCount= */ 4000)).isFalse();
  }

  @Test
  public void handleContextCaching_gemini3ResourceNameBelowMinimum_doesNotCreateCache() {
    assertThat(
            createsCache(
                "projects/p/locations/l/models/gemini-3-pro", /* previousTokenCount= */ 4000))
        .isFalse();
  }

  @Test
  public void handleContextCaching_opaqueModel_appliesNoMinimum() {
    assertThat(createsCache("projects/p/locations/l/endpoints/123", /* previousTokenCount= */ 10))
        .isTrue();
  }

  @Test
  public void handleContextCaching_smallPrefixOfLargePrompt_doesNotCreateCache() {
    String model = "gemini-2.5-flash";
    GeminiContextCacheManager manager = new GeminiContextCacheManager(api.client(), model);
    Content largeQuestion = text("user", "x".repeat(400_000));
    CacheMetadata firstTurn =
        manager.handleContextCaching(request(USER_1).model(model).build()).blockingGet().metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, largeQuestion)
            .model(model)
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(100_000)
            .build();

    CacheResult result = manager.handleContextCaching(request).blockingGet();

    assertThat(api.kinds()).isEmpty();
    assertThat(result.metadata().cacheName()).isEmpty();
  }

  @Test
  public void handleContextCaching_onlyBinaryParts_trustsPreviousTokenCount() {
    String model = "gemini-2.5-flash";
    GeminiContextCacheManager manager = new GeminiContextCacheManager(api.client(), model);
    Content image =
        Content.builder()
            .role("user")
            .parts(Part.fromBytes(new byte[] {1, 2}, "image/png"))
            .build();
    Content answer =
        Content.builder().role("model").parts(Part.fromBytes(new byte[] {3}, "image/png")).build();
    LlmRequest firstTurn =
        LlmRequest.builder()
            .model(model)
            .contents(ImmutableList.of(image))
            .cacheConfig(CACHE_CONFIG)
            .build();
    CacheMetadata firstMetadata = manager.handleContextCaching(firstTurn).blockingGet().metadata();
    LlmRequest request =
        firstTurn.toBuilder()
            .contents(ImmutableList.of(image, answer, image))
            .cacheMetadata(firstMetadata)
            .cacheableContentsTokenCount(3000)
            .build();

    CacheResult result = manager.handleContextCaching(request).blockingGet();

    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE);
    JsonNode create = api.bodies(Kind.CREATE_CACHE).get(0);
    assertThat(create.has("systemInstruction")).isFalse();
    assertThat(create.has("tools")).isFalse();
    assertThat(create.get("contents")).hasSize(2);
    assertThat(result.request().config().get().cachedContent()).hasValue("cachedContents/cache-1");
    assertThat(result.request().contents()).containsExactly(image);
  }

  @Test
  public void handleContextCaching_creationFails_sendsRequestUncachedWithGrownFingerprint() {
    api.failNextCreate(400);
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, USER_2)
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(5000)
            .build();

    CacheResult result = handle(request);

    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE);
    assertThat(result.request()).isEqualTo(request);
    assertThat(result.metadata().cacheName()).isEmpty();
    assertThat(result.metadata().contentsCount()).isEqualTo(2);
    // The grown prefix is kept, so the next invocation tries to create the cache again.
    var unused = handle(request.toBuilder().cacheMetadata(result.metadata()).build());
    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE, Kind.CREATE_CACHE);
  }

  @Test
  public void handleContextCaching_deletionFails_stillCreatesNewCache() {
    api.failNextDelete(500);
    LlmRequest request = request(USER_1, MODEL_1, USER_2).cacheableContentsTokenCount(5000).build();
    CacheMetadata cache = activeCacheFor(request, NOW.minusSeconds(1), /* invocationsUsed= */ 2);

    CacheResult result = handle(request.toBuilder().cacheMetadata(cache).build());

    assertThat(api.kinds()).containsExactly(Kind.DELETE_CACHE, Kind.CREATE_CACHE).inOrder();
    assertThat(result.metadata().cacheName()).hasValue("cachedContents/cache-1");
  }

  @Test
  public void handleContextCaching_serverReturnsEmptyCacheName_sendsRequestUncached() {
    api.answerNextCreateWith("{\"name\": \"\"}");
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, USER_2)
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(5000)
            .build();

    CacheResult result = handle(request);

    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE);
    assertThat(result.request()).isEqualTo(request);
    assertThat(result.metadata().cacheName()).isEmpty();
  }

  @Test
  public void handleContextCaching_cacheExpiringNow_isReplaced() {
    LlmRequest request = request(USER_1, MODEL_1, USER_2).cacheableContentsTokenCount(5000).build();
    CacheMetadata cache = activeCacheFor(request, NOW, /* invocationsUsed= */ 1);

    var unused = handle(request.toBuilder().cacheMetadata(cache).build());

    assertThat(api.kinds()).containsExactly(Kind.DELETE_CACHE, Kind.CREATE_CACHE).inOrder();
  }

  @Test
  public void handleContextCaching_previousRequestAtMinTokens_createsCache() {
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, USER_2)
            .cacheConfig(new ContextCacheConfig(10, Duration.ofMinutes(30), 5000))
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(5000)
            .build();

    CacheResult result = handle(request);

    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE);
    assertThat(result.metadata().cacheName()).hasValue("cachedContents/cache-1");
  }

  @Test
  public void handleContextCaching_previousCountAboveCurrent_keepsPreviousCount() {
    LlmRequest request = request(USER_1, MODEL_1).build();
    CacheMetadata previous =
        CacheMetadata.builder().fingerprint(fingerprint(request)).contentsCount(5).build();

    CacheResult result =
        handle(
            request.toBuilder().cacheMetadata(previous).cacheableContentsTokenCount(5000).build());

    assertThat(api.bodies(Kind.CREATE_CACHE).get(0).get("contents")).hasSize(2);
    assertThat(result.metadata().contentsCount()).isEqualTo(5);
    assertThat(result.request().contents()).containsExactly(MODEL_1);
  }

  @Test
  public void handleContextCaching_interruptedWhileCreating_propagatesAndKeepsInterrupt() {
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, USER_2)
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(5000)
            .build();
    api.delayCacheCalls(Duration.ofSeconds(2));

    Thread.currentThread().interrupt();
    RuntimeException thrown = assertThrows(RuntimeException.class, () -> handle(request));

    assertThat(Thread.interrupted()).isTrue();
    assertThat(thrown).hasCauseThat().isInstanceOf(InterruptedException.class);
  }

  @Test
  public void handleContextCaching_interruptedWhileDeleting_propagatesAndKeepsInterrupt() {
    LlmRequest request = request(USER_1, MODEL_1, USER_2).cacheableContentsTokenCount(5000).build();
    CacheMetadata cache = activeCacheFor(request, NOW.minusSeconds(1), /* invocationsUsed= */ 1);
    LlmRequest withExpiredCache = request.toBuilder().cacheMetadata(cache).build();
    api.delayCacheCalls(Duration.ofSeconds(2));

    Thread.currentThread().interrupt();
    RuntimeException thrown = assertThrows(RuntimeException.class, () -> handle(withExpiredCache));

    assertThat(Thread.interrupted()).isTrue();
    assertThat(thrown).hasCauseThat().isInstanceOf(InterruptedException.class);
  }

  @Test
  public void handleContextCaching_serverOmitsExpireTime_expiresAfterTtl() {
    api.omitExpireTime();
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();

    CacheMetadata created =
        handle(
                request(USER_1, MODEL_1, USER_2)
                    .cacheMetadata(firstTurn)
                    .cacheableContentsTokenCount(5000)
                    .build())
            .metadata();

    assertThat(created.createdAt()).hasValue(NOW);
    assertThat(created.expireTime()).hasValue(NOW.plus(CACHE_CONFIG.ttl()));
  }

  @Test
  public void handleContextCaching_cacheCoversEveryContent_stillSendsTheFinalContent() {
    LlmRequest request = request(USER_1, MODEL_1).build();
    CacheMetadata cache = activeCacheFor(request, inOneHour(), /* invocationsUsed= */ 1);

    CacheResult result = handle(request.toBuilder().cacheMetadata(cache).build());

    assertThat(cache.contentsCount()).isEqualTo(2);
    assertThat(result.request().contents()).containsExactly(MODEL_1);
  }

  @Test
  public void handleContextCaching_requestWithoutConfig_cachesOnlyContents() {
    LlmRequest firstTurn =
        LlmRequest.builder()
            .model(MODEL)
            .contents(ImmutableList.of(USER_1))
            .cacheConfig(CACHE_CONFIG)
            .build();
    CacheMetadata firstMetadata = handle(firstTurn).metadata();

    CacheResult result =
        handle(
            firstTurn.toBuilder()
                .contents(ImmutableList.of(USER_1, MODEL_1, USER_2))
                .cacheMetadata(firstMetadata)
                .cacheableContentsTokenCount(5000)
                .build());

    JsonNode create = api.bodies(Kind.CREATE_CACHE).get(0);
    assertThat(create.has("systemInstruction")).isFalse();
    assertThat(create.has("tools")).isFalse();
    assertThat(create.has("toolConfig")).isFalse();
    assertThat(result.request().config())
        .hasValue(GenerateContentConfig.builder().cachedContent("cachedContents/cache-1").build());
  }

  @Test
  public void handleContextCaching_serverReturnsNoCacheName_sendsRequestUncached() {
    api.answerNextCreateWith("{}");
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    LlmRequest request =
        request(USER_1, MODEL_1, USER_2)
            .cacheMetadata(firstTurn)
            .cacheableContentsTokenCount(5000)
            .build();

    CacheResult result = handle(request);

    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE);
    assertThat(result.request()).isEqualTo(request);
    assertThat(result.metadata().cacheName()).isEmpty();
    assertThat(result.metadata().contentsCount()).isEqualTo(2);
  }

  @Test
  public void handleContextCaching_noSettledContents_cachesOnlyInstructionAndTools() {
    CacheMetadata firstTurn = handle(request(USER_1).build()).metadata();
    Content followUp = text("user", "Here is some extra context.");

    CacheResult result =
        handle(
            request(USER_1, followUp)
                .cacheMetadata(firstTurn)
                .cacheableContentsTokenCount(5000)
                .build());

    JsonNode create = api.bodies(Kind.CREATE_CACHE).get(0);
    assertThat(create.has("contents")).isFalse();
    assertThat(create.get("systemInstruction").toString()).contains(INSTRUCTION);
    assertThat(result.metadata().contentsCount()).isEqualTo(0);
    assertThat(result.request().contents()).containsExactly(USER_1, followUp).inOrder();
    assertThat(result.request().config().get().systemInstruction()).isEmpty();
  }

  @Test
  public void handleContextCaching_emptyToolList_isTreatedAsNoTools() {
    GenerateContentConfig emptyTools = CONFIG.toBuilder().tools(ImmutableList.of()).build();
    GenerateContentConfig noTools = CONFIG.toBuilder().clearTools().build();
    CacheMetadata firstTurn = handle(request(USER_1).config(emptyTools).build()).metadata();

    var unused =
        handle(
            request(USER_1, MODEL_1, USER_2)
                .config(emptyTools)
                .cacheMetadata(firstTurn)
                .cacheableContentsTokenCount(5000)
                .build());

    assertThat(firstTurn.fingerprint())
        .isEqualTo(fingerprint(request(USER_1).config(noTools).build()));
    assertThat(api.bodies(Kind.CREATE_CACHE).get(0).has("tools")).isFalse();
  }

  @Test
  public void handleContextCaching_gemini25WithZeroPreviousTokens_doesNotCreateCache() {
    assertThat(createsCache("gemini-2.5-flash", /* previousTokenCount= */ 0)).isFalse();
  }

  @Test
  public void fingerprint_trailingUserContent_isIgnored() {
    assertThat(fingerprint(request(USER_1, MODEL_1, USER_2).build()))
        .isEqualTo(fingerprint(request(USER_1, MODEL_1, text("user", "Other question")).build()));
  }

  @Test
  public void fingerprint_reorderedTools_isUnchanged() {
    LlmRequest request =
        request(USER_1)
            .config(
                CONFIG.toBuilder()
                    .tools(functionTool("b_tool", "a_tool"), SEARCH_TOOL, functionTool("c_tool"))
                    .build())
            .build();
    LlmRequest reordered =
        request(USER_1)
            .config(
                CONFIG.toBuilder()
                    .tools(functionTool("c_tool"), functionTool("a_tool", "b_tool"), SEARCH_TOOL)
                    .build())
            .build();

    assertThat(fingerprint(reordered)).isEqualTo(fingerprint(request));
  }

  @Test
  public void fingerprint_differentTools_differs() {
    LlmRequest request = request(USER_1).build();
    LlmRequest otherTools =
        request.toBuilder().config(CONFIG.toBuilder().tools(functionTool("other")).build()).build();

    assertThat(fingerprint(otherTools)).isNotEqualTo(fingerprint(request));
  }

  @Test
  public void fingerprint_differentToolConfig_differs() {
    LlmRequest request = request(USER_1).build();
    LlmRequest otherToolConfig =
        request.toBuilder()
            .config(
                CONFIG.toBuilder()
                    .toolConfig(
                        ToolConfig.builder()
                            .functionCallingConfig(
                                FunctionCallingConfig.builder()
                                    .mode(FunctionCallingConfigMode.Known.NONE))
                            .build())
                    .build())
            .build();

    assertThat(fingerprint(otherToolConfig)).isNotEqualTo(fingerprint(request));
  }

  @Test
  public void fingerprint_differentModel_differs() {
    LlmRequest request = request(USER_1).build();
    GeminiContextCacheManager otherModel =
        new GeminiContextCacheManager(api.client(), "gemini-other-model");

    assertThat(otherModel.handleContextCaching(request).blockingGet().metadata().fingerprint())
        .isNotEqualTo(fingerprint(request));
  }

  @Test
  public void fingerprint_differentVertexProjectOrLocation_differs() {
    LlmRequest request = request(USER_1).build();

    String base = vertexFingerprint("project-a", "us-central1", request);

    assertThat(vertexFingerprint("project-b", "us-central1", request)).isNotEqualTo(base);
    assertThat(vertexFingerprint("project-a", "europe-west4", request)).isNotEqualTo(base);
    assertThat(vertexFingerprint("project-a", "us-central1", request)).isEqualTo(base);
  }

  @Test
  public void fingerprint_differentBackend_differs() {
    LlmRequest request = request(USER_1).build();
    Client vertexClient = Client.builder().vertexAI(true).apiKey("test-api-key").build();
    GeminiContextCacheManager vertex = new GeminiContextCacheManager(vertexClient, MODEL);

    assertThat(vertex.handleContextCaching(request).blockingGet().metadata().fingerprint())
        .isNotEqualTo(fingerprint(request));
  }

  private static String vertexFingerprint(String project, String location, LlmRequest request) {
    Client client =
        Client.builder()
            .vertexAI(true)
            .project(project)
            .location(location)
            .credentials(GoogleCredentials.create(new AccessToken("test-token", null)))
            .build();
    return new GeminiContextCacheManager(client, MODEL)
        .handleContextCaching(request)
        .blockingGet()
        .metadata()
        .fingerprint();
  }

  private CacheResult handle(LlmRequest request) {
    return manager.handleContextCaching(request).blockingGet();
  }

  private String fingerprint(LlmRequest request) {
    return handle(request).metadata().fingerprint();
  }

  /** Returns metadata for an existing cache of the request's cacheable prefix. */
  private CacheMetadata activeCacheFor(
      LlmRequest request, Instant expireTime, int invocationsUsed) {
    return handle(request.toBuilder().cacheMetadata(null).build()).metadata().toBuilder()
        .cacheName(EXISTING_CACHE)
        .expireTime(expireTime)
        .invocationsUsed(invocationsUsed)
        .createdAt(NOW.minus(Duration.ofMinutes(5)))
        .build();
  }

  /** Returns whether the second turn of a conversation with {@code model} creates a cache. */
  private boolean createsCache(String model, int previousTokenCount) {
    GeminiContextCacheManager manager = new GeminiContextCacheManager(api.client(), model);
    CacheMetadata firstTurn =
        manager.handleContextCaching(request(USER_1).model(model).build()).blockingGet().metadata();
    CacheResult result =
        manager
            .handleContextCaching(
                request(USER_1, MODEL_1, USER_2)
                    .model(model)
                    .cacheMetadata(firstTurn)
                    .cacheableContentsTokenCount(previousTokenCount)
                    .build())
            .blockingGet();
    return result.metadata().cacheName().isPresent();
  }

  private static LlmRequest.Builder request(Content... contents) {
    return LlmRequest.builder()
        .model(MODEL)
        .contents(ImmutableList.copyOf(contents))
        .config(CONFIG)
        .cacheConfig(CACHE_CONFIG);
  }

  private static Instant inOneHour() {
    return NOW.plus(Duration.ofHours(1));
  }

  private static Content text(String role, String text) {
    return Content.builder().role(role).parts(Part.fromText(text)).build();
  }

  private static Tool functionTool(String... names) {
    return Tool.builder()
        .functionDeclarations(
            Arrays.stream(names)
                .map(name -> FunctionDeclaration.builder().name(name).description(name).build())
                .collect(toImmutableList()))
        .build();
  }

  private static ImmutableList<String> texts(Content... contents) {
    return Arrays.stream(contents)
        .map(content -> content.parts().get().get(0).text().get())
        .collect(toImmutableList());
  }

  private static ImmutableList<String> texts(JsonNode contents) {
    return stream(contents)
        .flatMap(content -> stream(content.get("parts")))
        .map(part -> part.path("text").asText())
        .collect(toImmutableList());
  }
}
