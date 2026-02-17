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

import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.TailRetentionEventCompactor;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;

/** Request processor that performs event compaction. */
public class Compaction implements RequestProcessor {

  @Override
  public Single<RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    Optional<EventsCompactionConfig> configOpt = context.eventsCompactionConfig();

    if (configOpt.isEmpty()) {
      return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
    }

    EventsCompactionConfig config = configOpt.get();

    if (config.tokenThreshold() == null || config.eventRetentionSize() == null) {
      return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
    }

    // Extract out the retention size and token threshold from the new config.
    int retentionSize = config.eventRetentionSize();
    int tokenThreshold = config.tokenThreshold();

    // Summarizer will not be missing since the runner will always add a default one if missing.
    TailRetentionEventCompactor compactor =
        new TailRetentionEventCompactor(config.summarizer(), retentionSize, tokenThreshold);

    return compactor
        .compact(context.session(), context.sessionService())
        .andThen(
            Single.just(
                RequestProcessor.RequestProcessingResult.create(request, ImmutableList.of())));
  }
}
