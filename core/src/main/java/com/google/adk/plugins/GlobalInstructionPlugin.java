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
package com.google.adk.plugins;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.utils.InstructionUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Plugin that provides global instructions functionality at the App level.
 *
 * <p>Global instructions are applied to all agents in the application, providing a consistent way
 * to set application-wide instructions, identity, or personality. Global instructions can be
 * provided as a static string, or as a function that resolves the instruction based on the {@link
 * CallbackContext}.
 *
 * <p>The plugin operates through the before_model_callback, allowing it to modify LLM requests
 * before they are sent to the model by prepending the global instruction to any existing system
 * instructions provided by the agent.
 */
public class GlobalInstructionPlugin extends BasePlugin {

  private final Function<CallbackContext, Maybe<String>> instructionProvider;

  private static Function<CallbackContext, Maybe<String>> createInstructionProvider(
      String globalInstruction) {
    return callbackContext -> {
      if (globalInstruction == null) {
        return Maybe.empty();
      }
      return InstructionUtils.injectSessionState(
              callbackContext.invocationContext(), globalInstruction)
          .toMaybe();
    };
  }

  public GlobalInstructionPlugin(String globalInstruction) {
    this(globalInstruction, "global_instruction");
  }

  public GlobalInstructionPlugin(String globalInstruction, String name) {
    this(createInstructionProvider(globalInstruction), name);
  }

  public GlobalInstructionPlugin(Function<CallbackContext, Maybe<String>> instructionProvider) {
    this(instructionProvider, "global_instruction");
  }

  public GlobalInstructionPlugin(
      Function<CallbackContext, Maybe<String>> instructionProvider, String name) {
    super(name);
    this.instructionProvider = instructionProvider;
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    return instructionProvider
        .apply(callbackContext)
        .filter(instruction -> !instruction.isEmpty())
        .flatMap(
            instruction -> {
              // Get mutable config, or create one if it doesn't exist.
              GenerateContentConfig config =
                  llmRequest.config().orElseGet(GenerateContentConfig.builder()::build);

              // Get existing system instruction parts, if any.
              Optional<Content> systemInstruction = config.systemInstruction();
              List<Part> existingParts =
                  systemInstruction.flatMap(Content::parts).orElse(ImmutableList.of());

              // Prepend the global instruction to the existing system instruction parts.
              // If there are existing instructions, add two newlines between the global
              // instruction and the existing instructions.
              ImmutableList.Builder<Part> newPartsBuilder = ImmutableList.<Part>builder();
              if (existingParts.isEmpty()) {
                newPartsBuilder.add(Part.fromText(instruction));
              } else {
                newPartsBuilder.add(Part.fromText(instruction + "\n\n"));
                newPartsBuilder.addAll(existingParts);
              }

              // Build the new system instruction content.
              Content.Builder newSystemInstructionBuilder = Content.builder();
              systemInstruction.flatMap(Content::role).ifPresent(newSystemInstructionBuilder::role);
              newSystemInstructionBuilder.parts(newPartsBuilder.build());

              // Update llmRequest with new config.
              llmRequest.config(
                  config.toBuilder()
                      .systemInstruction(newSystemInstructionBuilder.build())
                      .build());
              return Maybe.<LlmResponse>empty();
            });
  }
}
