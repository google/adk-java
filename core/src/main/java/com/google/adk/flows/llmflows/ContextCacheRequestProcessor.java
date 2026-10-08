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

import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.CacheMetadata;
import com.google.adk.models.LlmRequest;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RequestProcessor} that enables context caching when the app configures it. It puts the
 * config, the agent's latest cache metadata and its previous prompt token count on the request; the
 * model creates, reuses and deletes the caches.
 */
final class ContextCacheRequestProcessor implements RequestProcessor {

  private static final Logger logger = LoggerFactory.getLogger(ContextCacheRequestProcessor.class);

  @Override
  public Single<RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    Optional<ContextCacheConfig> cacheConfig = context.contextCacheConfig();
    if (cacheConfig.isEmpty()) {
      return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
    }

    String agentName = context.agent().name();
    CacheMetadata cacheMetadata = null;
    Integer previousTokenCount = null;
    for (Event event : context.session().immutableEvents().reverse()) {
      if (!agentName.equals(event.author())) {
        continue;
      }
      if (cacheMetadata == null && event.cacheMetadata().isPresent()) {
        cacheMetadata = countInvocation(event, context.invocationId());
      }
      if (previousTokenCount == null) {
        previousTokenCount =
            event
                .usageMetadata()
                .flatMap(GenerateContentResponseUsageMetadata::promptTokenCount)
                .orElse(null);
      }
      if (cacheMetadata != null && previousTokenCount != null) {
        break;
      }
    }
    if (cacheMetadata != null) {
      logger.debug("Found cache metadata for agent {}: {}", agentName, cacheMetadata);
    }
    if (previousTokenCount != null) {
      logger.debug(
          "Found previous prompt token count for agent {}: {}", agentName, previousTokenCount);
    }
    logger.debug("Context caching enabled for agent {}", agentName);

    LlmRequest updatedRequest =
        request.toBuilder()
            .cacheConfig(cacheConfig.get())
            .cacheMetadata(cacheMetadata)
            .cacheableContentsTokenCount(previousTokenCount)
            .build();
    return Single.just(RequestProcessingResult.create(updatedRequest, ImmutableList.of()));
  }

  /**
   * Returns the event's cache metadata, counting one more use when it names an active cache from an
   * earlier invocation.
   */
  private static CacheMetadata countInvocation(Event event, String invocationId) {
    CacheMetadata metadata = event.cacheMetadata().get();
    if (Strings.isNullOrEmpty(event.invocationId())
        || event.invocationId().equals(invocationId)
        || metadata.cacheName().isEmpty()) {
      return metadata;
    }
    return metadata.toBuilder().invocationsUsed(metadata.invocationsUsed().get() + 1).build();
  }
}
