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
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.models.FakeGeminiApi.Kind;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.CachedContent;
import com.google.genai.types.CreateCachedContentConfig;
import com.google.genai.types.DeleteCachedContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FakeGeminiApiTest {

  private static final String MODEL = "gemini-test-model";

  private final FakeGeminiApi api = new FakeGeminiApi();
  private final Client client = api.client();

  @Test
  public void generateContent_returnsNumberedAnswers() {
    String first = client.models.generateContent(MODEL, "Hi", null).text();
    String second = client.models.generateContent(MODEL, "Hi again", null).text();

    assertThat(first).isEqualTo("Answer 1");
    assertThat(second).isEqualTo("Answer 2");
    assertThat(api.kinds()).containsExactly(Kind.GENERATE, Kind.GENERATE);
    assertThat(api.bodies(Kind.GENERATE).get(0).at("/contents/0/parts/0/text").asText())
        .isEqualTo("Hi");
  }

  @Test
  public void generateContentStream_returnsPartialThenFinalAnswer() {
    ImmutableList<String> chunks =
        ImmutableList.copyOf(client.models.generateContentStream(MODEL, "Hi", null)).stream()
            .map(GenerateContentResponse::text)
            .collect(toImmutableList());

    assertThat(chunks).containsExactly("Answer ", "1").inOrder();
    assertThat(api.kinds()).containsExactly(Kind.GENERATE);
  }

  @Test
  public void createCache_returnsNumberedCacheThatExpiresInAnHour() {
    Instant before = Instant.now();

    CachedContent first = createCache();
    CachedContent second = createCache();

    assertThat(first.name()).hasValue("cachedContents/cache-1");
    assertThat(second.name()).hasValue("cachedContents/cache-2");
    assertThat(first.expireTime()).hasValue(api.expireTime(1));
    assertThat(api.expireTime(1)).isAtLeast(before.plus(Duration.ofHours(1)));
    assertThat(api.kinds()).containsExactly(Kind.CREATE_CACHE, Kind.CREATE_CACHE);
  }

  @Test
  public void failNextCreate_failsOnlyTheNextCreate() {
    api.failNextCreate(400);

    ApiException error = assertThrows(ApiException.class, this::createCache);
    CachedContent next = createCache();

    assertThat(error.code()).isEqualTo(400);
    assertThat(next.name()).hasValue("cachedContents/cache-1");
  }

  @Test
  public void failNextDelete_failsOnlyTheNextDelete() {
    api.failNextDelete(500);
    DeleteCachedContentConfig config = DeleteCachedContentConfig.builder().build();

    ApiException error =
        assertThrows(ApiException.class, () -> client.caches.delete("cachedContents/a", config));
    var unused = client.caches.delete("cachedContents/b", config);

    assertThat(error.code()).isEqualTo(500);
    assertThat(api.paths(Kind.DELETE_CACHE).get(1)).endsWith("/cachedContents/b");
  }

  @Test
  public void omitExpireTime_returnsCacheWithoutExpireTime() {
    api.omitExpireTime();

    assertThat(createCache().expireTime()).isEmpty();
  }

  @Test
  public void answerNextCreateWith_returnsThatBodyOnce() {
    api.answerNextCreateWith("{\"name\": \"cachedContents/custom\"}");

    assertThat(createCache().name()).hasValue("cachedContents/custom");
    assertThat(createCache().name()).hasValue("cachedContents/cache-1");
  }

  @Test
  public void headers_recordsRequestHeaders() {
    CreateCachedContentConfig config =
        CreateCachedContentConfig.builder()
            .httpOptions(HttpOptions.builder().headers(ImmutableMap.of("x-test", "yes")).build())
            .build();

    var unused = client.caches.create(MODEL, config);

    assertThat(api.headers(Kind.CREATE_CACHE).get(0).get("x-test")).isEqualTo("yes");
  }

  private CachedContent createCache() {
    return client.caches.create(MODEL, CreateCachedContentConfig.builder().build());
  }
}
