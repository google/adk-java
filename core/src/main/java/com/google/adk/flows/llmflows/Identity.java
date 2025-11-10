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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;

/** {@link RequestProcessor} that gives the agent identity from the framework */
public final class Identity implements RequestProcessor {

  public Identity() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    BaseAgent agent = context.agent();
    StringBuilder builder =
        new StringBuilder()
            .append("You are an agent. Your internal name is ")
            .append("\"")
            .append(agent.name())
            .append("\"")
            .append(".");
    if (!Strings.isNullOrEmpty(agent.description())) {
      builder.append(" The description about you is \"").append(agent.description()).append("\".");
    }
    return Single.just(
        RequestProcessor.RequestProcessingResult.create(
            request.toBuilder().appendInstructions(ImmutableList.of(builder.toString())).build(),
            ImmutableList.of()));
  }
}
