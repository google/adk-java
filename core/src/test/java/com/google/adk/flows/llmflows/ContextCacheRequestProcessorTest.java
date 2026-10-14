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

package com.google.adk.flows.llmflows;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.models.CacheMetadata;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ContextCacheRequestProcessorTest {

  private static final ContextCacheConfig CACHE_CONFIG =
      new ContextCacheConfig(5, Duration.ofMinutes(10), 1024);
  private static final CacheMetadata ACTIVE_CACHE =
      CacheMetadata.builder()
          .fingerprint("active")
          .contentsCount(3)
          .cacheName("cachedContents/42")
          .expireTime(Instant.ofEpochSecond(2_000_000_000L))
          .invocationsUsed(2)
          .createdAt(Instant.ofEpochSecond(1_999_999_000L))
          .build();
  private static final CacheMetadata FINGERPRINT_ONLY =
      CacheMetadata.builder().fingerprint("prefix").contentsCount(1).build();
  private static final LlmRequest REQUEST = LlmRequest.builder().model("gemini-2.5-flash").build();

  private final ContextCacheRequestProcessor processor = new ContextCacheRequestProcessor();
  private final InvocationContext context =
      createInvocationContext(createTestAgent(createTestLlm(LlmResponse.builder().build())))
          .toBuilder()
          .contextCacheConfig(CACHE_CONFIG)
          .build();
  private final String agentName = context.agent().name();

  @Test
  public void processRequest_withoutCacheConfig_returnsRequestUnchanged() {
    InvocationContext uncached =
        createInvocationContext(createTestAgent(createTestLlm(LlmResponse.builder().build())));
    uncached.session().addEvent(event(agentName, "earlier", ACTIVE_CACHE, 4000));

    RequestProcessingResult result = processor.processRequest(uncached, REQUEST).blockingGet();

    assertThat(result.updatedRequest()).isEqualTo(REQUEST);
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_withoutEvents_setsOnlyCacheConfig() {
    LlmRequest request = process();

    assertThat(request.cacheConfig()).hasValue(CACHE_CONFIG);
    assertThat(request.cacheMetadata()).isEmpty();
    assertThat(request.cacheableContentsTokenCount()).isEmpty();
  }

  @Test
  public void processRequest_activeCacheFromEarlierInvocation_countsOneMoreUse() {
    addEvent(event(agentName, "earlier", ACTIVE_CACHE, 4000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata())
        .hasValue(ACTIVE_CACHE.toBuilder().invocationsUsed(3).build());
    assertThat(request.cacheableContentsTokenCount()).hasValue(4000);
  }

  @Test
  public void processRequest_activeCacheFromCurrentInvocation_keepsUseCount() {
    addEvent(event(agentName, context.invocationId(), ACTIVE_CACHE, 4000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata()).hasValue(ACTIVE_CACHE);
  }

  @Test
  public void processRequest_eventWithoutInvocationId_keepsUseCount() {
    addEvent(event(agentName, /* invocationId= */ null, ACTIVE_CACHE, 4000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata()).hasValue(ACTIVE_CACHE);
  }

  @Test
  public void processRequest_eventWithEmptyInvocationId_keepsUseCount() {
    addEvent(event(agentName, "", ACTIVE_CACHE, 4000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata()).hasValue(ACTIVE_CACHE);
  }

  @Test
  public void processRequest_fingerprintOnlyFromEarlierInvocation_isReturnedAsIs() {
    addEvent(event(agentName, "earlier", FINGERPRINT_ONLY, 4000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata()).hasValue(FINGERPRINT_ONLY);
  }

  @Test
  public void processRequest_otherAgentsEvents_areIgnored() {
    addEvent(event(agentName, "earlier", FINGERPRINT_ONLY, 1000));
    addEvent(event("other agent", "earlier", ACTIVE_CACHE, 9000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata()).hasValue(FINGERPRINT_ONLY);
    assertThat(request.cacheableContentsTokenCount()).hasValue(1000);
  }

  @Test
  public void processRequest_severalEvents_usesTheLatest() {
    addEvent(event(agentName, "first", FINGERPRINT_ONLY, 1000));
    addEvent(event(agentName, "second", ACTIVE_CACHE, 2000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata())
        .hasValue(ACTIVE_CACHE.toBuilder().invocationsUsed(3).build());
    assertThat(request.cacheableContentsTokenCount()).hasValue(2000);
  }

  @Test
  public void processRequest_latestEventsMissingOneValue_takesItFromOlderEvents() {
    addEvent(event(agentName, "first", FINGERPRINT_ONLY, /* promptTokenCount= */ null));
    addEvent(event(agentName, "second", /* cacheMetadata= */ null, 3000));

    LlmRequest request = process();

    assertThat(request.cacheMetadata()).hasValue(FINGERPRINT_ONLY);
    assertThat(request.cacheableContentsTokenCount()).hasValue(3000);
  }

  @Test
  public void processRequest_latestEventWithoutUsage_keepsItsMetadataOverOlderMetadata() {
    addEvent(event(agentName, "first", ACTIVE_CACHE, 1000));
    addEvent(event(agentName, "second", FINGERPRINT_ONLY, /* promptTokenCount= */ null));

    LlmRequest request = process();

    assertThat(request.cacheMetadata()).hasValue(FINGERPRINT_ONLY);
    assertThat(request.cacheableContentsTokenCount()).hasValue(1000);
  }

  private LlmRequest process() {
    RequestProcessingResult result = processor.processRequest(context, REQUEST).blockingGet();
    assertThat(result.events()).isEmpty();
    return result.updatedRequest();
  }

  private void addEvent(Event event) {
    context.session().addEvent(event);
  }

  private static Event event(
      String author,
      @Nullable String invocationId,
      @Nullable CacheMetadata cacheMetadata,
      @Nullable Integer promptTokenCount) {
    Event.Builder event =
        Event.builder().id(Event.generateEventId()).author(author).cacheMetadata(cacheMetadata);
    if (invocationId != null) {
      event.invocationId(invocationId);
    }
    if (promptTokenCount != null) {
      event.usageMetadata(
          GenerateContentResponseUsageMetadata.builder()
              .promptTokenCount(promptTokenCount)
              .build());
    }
    return event.build();
  }
}
