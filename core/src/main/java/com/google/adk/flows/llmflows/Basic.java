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
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.LiveConnectConfig;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;

/** {@link RequestProcessor} that handles basic information to build the LLM request. */
public final class Basic implements RequestProcessor {

  public Basic() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent)) {
      throw new IllegalArgumentException("Agent in InvocationContext is not an instance of Agent.");
    }
    LlmAgent agent = (LlmAgent) context.agent();
    String modelName =
        agent.resolvedModel().model().isPresent()
            ? agent.resolvedModel().model().get().model()
            : agent.resolvedModel().modelName().get();

    LiveConnectConfig.Builder liveConnectConfigBuilder =
        LiveConnectConfig.builder().responseModalities(context.runConfig().responseModalities());
    Optional.ofNullable(context.runConfig().speechConfig())
        .ifPresent(liveConnectConfigBuilder::speechConfig);
    Optional.ofNullable(context.runConfig().outputAudioTranscription())
        .ifPresent(liveConnectConfigBuilder::outputAudioTranscription);
    Optional.ofNullable(context.runConfig().inputAudioTranscription())
        .ifPresent(liveConnectConfigBuilder::inputAudioTranscription);

    LlmRequest.Builder builder =
        request.toBuilder()
            .model(modelName)
            .config(agent.generateContentConfig().orElse(GenerateContentConfig.builder().build()))
            .liveConnectConfig(liveConnectConfigBuilder.build());

    agent.outputSchema().ifPresent(builder::outputSchema);
    return Single.just(
        RequestProcessor.RequestProcessingResult.create(builder.build(), ImmutableList.of()));
  }
}
