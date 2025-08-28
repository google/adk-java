package com.google.adk.planners;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import java.util.List;
import java.util.Optional;

public class BuiltInPlanner implements BasePlanner {
  private ThinkingConfig cognitiveConfig;

  private BuiltInPlanner() {}

  private BuiltInPlanner(ThinkingConfig cognitiveConfig) {
    this.cognitiveConfig = cognitiveConfig;
  }

  public static BuiltInPlanner buildPlanner(ThinkingConfig cognitiveConfig) {
    return new BuiltInPlanner(cognitiveConfig);
  }

  @Override
  public Optional<String> generatePlanningInstruction(ReadonlyContext context, LlmRequest request) {
    return Optional.empty();
  }

  @Override
  public Optional<List<Part>> processPlanningResponse(
      CallbackContext context, List<Part> responseParts) {
    return Optional.empty();
  }

  /**
   * Configures the LLM request with thinking capabilities. This method modifies the request to
   * include the thinking configuration, enabling the model's native cognitive processing features.
   *
   * @param request the LLM request to configure
   */
  public LlmRequest applyThinkingConfig(LlmRequest request) {
    if (this.cognitiveConfig != null) {
      // Ensure config exists
      GenerateContentConfig.Builder configBuilder =
          request.config().map(GenerateContentConfig::toBuilder).orElse(GenerateContentConfig.builder());

      // Apply thinking configuration
      request =
          request.toBuilder()
              .config(configBuilder.thinkingConfig(this.cognitiveConfig).build())
              .build();
    }
    return request;
  }
}
