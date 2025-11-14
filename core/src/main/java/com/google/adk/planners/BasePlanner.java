package com.google.adk.planners;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Optional;

public interface BasePlanner {
  /**
   * Generates system instruction text for LLM planning requests.
   *
   * @param context readonly invocation context
   * @param request the LLM request being prepared
   * @return planning instruction text, or empty if no instruction needed
   */
  Optional<String> generatePlanningInstruction(ReadonlyContext context, LlmRequest request);

  /**
   * Processes and transforms LLM response parts for planning workflow.
   *
   * @param context callback context for the current invocation
   * @param responseParts list of response parts from the LLM
   * @return processed response parts, or empty if no processing required
   */
  Optional<List<Part>> processPlanningResponse(CallbackContext context, List<Part> responseParts);
}
