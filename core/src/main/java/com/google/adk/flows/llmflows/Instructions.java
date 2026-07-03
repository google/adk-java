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
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.utils.InstructionUtils;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;

/** {@link RequestProcessor} that handles instructions and global instructions for LLM flows. */
public final class Instructions implements RequestProcessor {
  public Instructions() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent agent)) {
      return Single.error(
          new IllegalArgumentException(
              "Agent in InvocationContext is not an instance of LlmAgent."));
    }
    ReadonlyContext readonlyContext = new ReadonlyContext(context);
    Single<LlmRequest.Builder> builderSingle = Single.just(request.toBuilder());

    // Process global instruction if applicable
    if (agent.rootAgent() instanceof LlmAgent rootAgent) {
      builderSingle =
          appendInstruction(
              builderSingle, context, rootAgent.canonicalGlobalInstruction(readonlyContext));
    }

    // Process agent-specific instruction
    builderSingle =
        appendInstruction(builderSingle, context, agent.canonicalInstruction(readonlyContext));

    return builderSingle.map(
        finalBuilder ->
            RequestProcessor.RequestProcessingResult.create(
                finalBuilder.build(), ImmutableList.of()));
  }

  private Single<LlmRequest.Builder> appendInstruction(
      Single<LlmRequest.Builder> builderSingle,
      InvocationContext context,
      Single<Map.Entry<String, Boolean>> instructionEntrySingle) {
    return builderSingle.flatMap(
        builder ->
            instructionEntrySingle.flatMap(
                instructionEntry -> {
                  String instruction = instructionEntry.getKey();
                  boolean bypassStateInjection = instructionEntry.getValue();
                  if (instruction.isEmpty()) {
                    return Single.just(builder);
                  }
                  if (bypassStateInjection) {
                    return Single.just(builder.appendInstructions(ImmutableList.of(instruction)));
                  }
                  return InstructionUtils.injectSessionState(context, instruction)
                      .map(
                          resolvedInstr ->
                              builder.appendInstructions(ImmutableList.of(resolvedInstr)));
                }));
  }
}
