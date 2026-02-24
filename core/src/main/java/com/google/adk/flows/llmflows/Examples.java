/*
 * Copyright 2025 Google LLC
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
import com.google.adk.agents.LlmAgent;
import com.google.adk.examples.ExampleUtils;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Single;

/** {@link RequestProcessor} that populates examples in LLM request. */
public final class Examples implements RequestProcessor {

  public Examples() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent)) {
      throw new IllegalArgumentException("Agent in InvocationContext is not an instance of Agent.");
    }
    LlmAgent agent = (LlmAgent) context.agent();
    LlmRequest.Builder builder = request.toBuilder();

    String query =
        context
            .userContent()
            .flatMap(Content::parts)
            .filter(parts -> !parts.isEmpty())
            .map(parts -> parts.get(0).text().orElse(""))
            .orElse("");
    agent
        .exampleProvider()
        .ifPresent(
            exampleProvider ->
                builder.appendInstructions(
                    ImmutableList.of(ExampleUtils.buildExampleSi(exampleProvider, query))));
    return Single.just(
        RequestProcessor.RequestProcessingResult.create(builder.build(), ImmutableList.of()));
  }
}
