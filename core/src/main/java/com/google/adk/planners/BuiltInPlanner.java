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

    private BuiltInPlanner() {
    }

    public BuiltInPlanner buildPlanner(ThinkingConfig cognitiveConfig) {
        this.cognitiveConfig = cognitiveConfig;
        return new BuiltInPlanner();
    }

    @Override
    public Optional<String> generatePlanningInstruction(ReadonlyContext context, LlmRequest request) {
        return Optional.empty();
    }

    @Override
    public Optional<List<Part>> processPlanningResponse(CallbackContext context, List<Part> responseParts) {
        return Optional.empty();
    }

    /**
     * Configures the LLM request with thinking capabilities.
     * This method modifies the request to include the thinking configuration,
     * enabling the model's native cognitive processing features.
     *
     * @param request the LLM request to configure
     */
    public LlmRequest applyThinkingConfig(LlmRequest request) {
        if (this.cognitiveConfig != null) {
            // Ensure config exists
            if (request.config().isEmpty()) {
                request = request.toBuilder().config(GenerateContentConfig.builder().build()).build();
            }

            // Apply thinking configuration
            request = request.toBuilder().config(GenerateContentConfig.builder().thinkingConfig(this.cognitiveConfig).build()).build();
        }
        return request;
    }
}
