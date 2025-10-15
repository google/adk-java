package com.google.adk.flows.llmflows;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.planners.BasePlanner;
import com.google.adk.planners.BuiltInPlanner;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class NLPlanning {

  static class NlPlanningRequestProcessor implements RequestProcessor {

    @Override
    public Single<RequestProcessingResult> processRequest(
        InvocationContext context, LlmRequest llmRequest) {

      if (!(context.agent() instanceof LlmAgent)) {
        throw new IllegalArgumentException(
            "Agent in InvocationContext is not an instance of LlmAgent.");
      }

      Optional<BasePlanner> plannerOpt = getPlanner(context);
      if (plannerOpt.isEmpty()) {
        return Single.just(RequestProcessor.RequestProcessingResult.create(llmRequest, ImmutableList.of()));
      }

      BasePlanner planner = plannerOpt.get();

      // Apply thinking configuration for built-in planners
      if (planner instanceof BuiltInPlanner) {
        llmRequest = ((BuiltInPlanner) planner).applyThinkingConfig(llmRequest);
      }

      // Build and append planning instruction
      Optional<String> planningInstruction =
          planner.generatePlanningInstruction(new ReadonlyContext(context), llmRequest);

      LlmRequest.Builder b = llmRequest.toBuilder();
      planningInstruction.ifPresent(s -> b.appendInstructions(ImmutableList.of(s)));
      llmRequest = b.build();

      // Remove thought annotations from request
      llmRequest = removeThoughtFromRequest(llmRequest);

      return Single.just(RequestProcessor.RequestProcessingResult.create(llmRequest, ImmutableList.of()));
    }
  }

  static class NlPlanningResponseProcessor implements ResponseProcessor {

    @Override
    public Single<ResponseProcessingResult> processResponse(
        InvocationContext context, LlmResponse llmResponse) {

      if (!(context.agent() instanceof LlmAgent)) {
        throw new IllegalArgumentException(
            "Agent in InvocationContext is not an instance of LlmAgent.");
      }

      // Validate response structure
      if (llmResponse == null || llmResponse.content().isEmpty()) {
        return Single.just(
            ResponseProcessor.ResponseProcessingResult.create(
                llmResponse, ImmutableList.of(), Optional.empty()));
      }

      Optional<BasePlanner> plannerOpt = getPlanner(context);
      if (plannerOpt.isEmpty()) {
        return Single.just(
            ResponseProcessor.ResponseProcessingResult.create(
                llmResponse, ImmutableList.of(), Optional.empty()));
      }

      BasePlanner planner = plannerOpt.get();
      LlmResponse.Builder responseBuilder = llmResponse.toBuilder();

      // Process the planning response
      CallbackContext callbackContext = new CallbackContext(context, null);
      Optional<List<Part>> processedParts =
          planner.processPlanningResponse(
              callbackContext, llmResponse.content().get().parts().orElse(List.of()));

      // Update response with processed parts
      if (processedParts.isPresent()) {
        Content.Builder contentBuilder = llmResponse.content().get().toBuilder();
        contentBuilder.parts(processedParts.get());
        responseBuilder.content(contentBuilder.build());
      }

      ImmutableList.Builder<Event> eventsBuilder = ImmutableList.builder();

      // Generate state update event if there are deltas
      if (callbackContext.state().hasDelta()) {
        Event stateUpdateEvent =
            Event.builder()
                .invocationId(context.invocationId())
                .author(context.agent().name())
                .branch(context.branch())
                .actions(callbackContext.eventActions())
                .build();

        eventsBuilder.add(stateUpdateEvent);
      }

      return Single.just(
          ResponseProcessor.ResponseProcessingResult.create(
              responseBuilder.build(), eventsBuilder.build(), Optional.empty()));
    }
  }

  /**
   * Retrieves the planner from the invocation context.
   *
   * @param invocationContext the current invocation context
   * @return optional planner instance, or empty if none available
   */
  private static Optional<BasePlanner> getPlanner(InvocationContext invocationContext) {
    if (!(invocationContext.agent() instanceof LlmAgent agent)) {
      return Optional.empty();
    }

    return agent.planner();
  }

  /**
   * Removes thought annotations from all parts in the LLM request.
   *
   * <p>This method iterates through all content parts and sets the thought field to false,
   * effectively removing thought markings from the request.
   *
   * @param llmRequest the LLM request to process
   */
  private static LlmRequest removeThoughtFromRequest(LlmRequest llmRequest) {
    if (llmRequest.contents() == null || llmRequest.contents().isEmpty()) {
      return llmRequest;
    }

    // Process each content and update its parts
    List<Content> updatedContents = llmRequest.contents().stream().map(content -> {
      if (content.parts().isEmpty()) {
        return content;
      }

      // Update all parts to set thought to false
      List<Part> updatedParts = content.parts().get().stream().map(part -> part.toBuilder().thought(false).build()).collect(Collectors.toList());

      // Return updated content with modified parts
      return content.toBuilder().parts(updatedParts).build();
    }).collect(Collectors.toList());

    // Return updated LlmRequest with modified contents
    return llmRequest.toBuilder().contents(updatedContents).build();
  }
}
