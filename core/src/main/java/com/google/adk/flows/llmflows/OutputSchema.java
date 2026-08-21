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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.SetModelResponseTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.utils.ModelNameUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Processor that handles output schema for agents with tools. */
public final class OutputSchema implements RequestProcessor {

  private static final Logger logger = LoggerFactory.getLogger(OutputSchema.class);

  public OutputSchema() {}

  @Override
  public Single<RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent)) {
      return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
    }
    LlmAgent agent = (LlmAgent) context.agent();
    String modelName = request.model().orElse("");

    if (agent.outputSchema().isEmpty()
        || agent.toolsUnion().isEmpty()
        || ModelNameUtils.canUseOutputSchemaWithTools(modelName)) {
      return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
    }

    // Add the set_model_response tool to handle structured output
    SetModelResponseTool setResponseTool = new SetModelResponseTool(agent.outputSchema().get());
    LlmRequest.Builder builder = request.toBuilder();

    return setResponseTool
        .processLlmRequest(builder, ToolContext.builder(context).build())
        .andThen(
            Single.fromCallable(
                () -> {
                  builder.appendInstructions(
                      ImmutableList.of(
                          "IMPORTANT: You have access to other tools, but you must provide your"
                              + " final response using the set_model_response tool with the"
                              + " required structured format. After using any other tools needed"
                              + " to complete the task, always call set_model_response with your"
                              + " final answer in the specified schema format."));
                  return RequestProcessingResult.create(builder.build(), ImmutableList.of());
                }));
  }

  /**
   * Check if function response contains set_model_response and extract JSON.
   *
   * @param functionResponseEvent The function response event to check.
   * @return JSON response string if set_model_response was called, Optional.empty() otherwise.
   */
  public static Optional<String> getStructuredModelResponse(Event functionResponseEvent) {
    for (FunctionResponse funcResponse : functionResponseEvent.functionResponses()) {
      if (Objects.equals(funcResponse.name().orElse(""), SetModelResponseTool.NAME)) {
        Object response = funcResponse.response();
        // The tool returns the args map directly.
        try {
          return Optional.of(JsonBaseModel.getMapper().writeValueAsString(response));
        } catch (JsonProcessingException e) {
          logger.error("Failed to serialize set_model_response result", e);
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Create a final model response event from set_model_response JSON.
   *
   * @param context The invocation context.
   * @param jsonResponse The JSON response from set_model_response tool.
   * @return A new Event that looks like a normal model response.
   */
  public static Event createFinalModelResponseEvent(
      InvocationContext context, String jsonResponse) {
    return Event.builder()
        .id(Event.generateEventId())
        .invocationId(context.invocationId())
        .author(context.agent().name())
        .branch(context.branch().orElse(null))
        .content(Content.builder().role("model").parts(Part.fromText(jsonResponse)).build())
        .build();
  }
}
