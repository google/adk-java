package com.google.adk.planners;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PlanReActPlanner implements BasePlanner {
  // ReAct structure tags
  private static final String PLANNING_TAG = "/*PLANNING*/";
  private static final String REPLANNING_TAG = "/*REPLANNING*/";
  private static final String REASONING_TAG = "/*REASONING*/";
  private static final String ACTION_TAG = "/*ACTION*/";
  private static final String FINAL_ANSWER_TAG = "/*FINAL_ANSWER*/";

  @Override
  public Optional<String> generatePlanningInstruction(ReadonlyContext context, LlmRequest request) {
    return Optional.of(buildNaturalLanguagePlannerInstruction());
  }

  @Override
  public Optional<List<Part>> processPlanningResponse(
      CallbackContext context, List<Part> responseParts) {
    if (responseParts == null || responseParts.isEmpty()) {
      return Optional.empty();
    }

    List<Part> preservedParts = new ArrayList<>();
    int firstFunctionCallIndex = -1;

    // Process parts until first function call
    for (int i = 0; i < responseParts.size(); i++) {
      Part part = responseParts.get(i);

      // Check for function call
      if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();

        // Skip function calls with empty names
        if (functionCall.name().isEmpty() || functionCall.name().get().trim().isEmpty()) {
          continue;
        }

        preservedParts.add(part);
        firstFunctionCallIndex = i;
        break;
      }

      // Handle non-function-call parts
      handleNonFunctionCallParts(part, preservedParts);
    }

    // Process remaining function calls if any
    if (firstFunctionCallIndex != -1) {
      int j = firstFunctionCallIndex + 1;
      while (j < responseParts.size()) {
        Part part = responseParts.get(j);
        if (part.functionCall().isPresent()) {
          preservedParts.add(part);
          j++;
        } else {
          break;
        }
      }
    }

    return Optional.of(ImmutableList.copyOf(preservedParts));
  }

  /**
   * Handles processing of non-function-call response parts.
   *
   * @param responsePart the part to process
   * @param preservedParts the mutable list to add processed parts to
   */
  private void handleNonFunctionCallParts(Part responsePart, List<Part> preservedParts) {
    if (responsePart.text().isEmpty()) {
      preservedParts.add(responsePart);
      return;
    }

    String text = responsePart.text().get();

    // Handle final answer tag specially
    if (text.contains(FINAL_ANSWER_TAG)) {
      String[] splitResult = splitByLastPattern(text, FINAL_ANSWER_TAG);
      String reasoningText = splitResult[0];
      String finalAnswerText = splitResult[1];

      // Add reasoning part if present
      if (!reasoningText.trim().isEmpty()) {
        Part reasoningPart = Part.builder().text(reasoningText).thought(true).build();
        preservedParts.add(reasoningPart);
      }

      // Add final answer part if present
      if (!finalAnswerText.trim().isEmpty()) {
        Part finalAnswerPart = Part.builder().text(finalAnswerText).build();
        preservedParts.add(finalAnswerPart);
      }
    } else {
      // Check if part should be marked as thought
      boolean isThought =
          text.startsWith(PLANNING_TAG)
              || text.startsWith(REASONING_TAG)
              || text.startsWith(ACTION_TAG)
              || text.startsWith(REPLANNING_TAG);

      Part.Builder partBuilder = responsePart.toBuilder();
      if (isThought) {
        partBuilder.thought(true);
      }

      preservedParts.add(partBuilder.build());
    }
  }

  /**
   * Splits text by the last occurrence of a separator.
   *
   * @param text the text to split
   * @param separator the separator to search for
   * @return array containing [before_separator + separator, after_separator]
   */
  private String[] splitByLastPattern(String text, String separator) {
    int index = text.lastIndexOf(separator);
    if (index == -1) {
      return new String[] {text, ""};
    }

    String before = text.substring(0, index + separator.length());
    String after = text.substring(index + separator.length());
    return new String[] {before, after};
  }

  /**
   * Builds the comprehensive natural language planner instruction.
   *
   * @return the complete system instruction for Plan-ReAct methodology
   */
  private String buildNaturalLanguagePlannerInstruction() {
    String highLevelPreamble =
        String.format(
            """
            When answering the question, try to leverage the available tools to gather the information instead of your memorized knowledge.

            Follow this process when answering the question: (1) first come up with a plan in natural language text format; (2) Then use tools to execute the plan and provide reasoning between tool code snippets to make a summary of current state and next step. Tool code snippets and reasoning should be interleaved with each other. (3) In the end, return one final answer.

            Follow this format when answering the question: (1) The planning part should be under %s. (2) The tool code snippets should be under %s, and the reasoning parts should be under %s. (3) The final answer part should be under %s.
            """,
            PLANNING_TAG, ACTION_TAG, REASONING_TAG, FINAL_ANSWER_TAG);

    String planningPreamble =
        String.format(
            """
            Below are the requirements for the planning:
            The plan is made to answer the user query if following the plan. The plan is coherent and covers all aspects of information from user query, and only involves the tools that are accessible by the agent. The plan contains the decomposed steps as a numbered list where each step should use one or multiple available tools. By reading the plan, you can intuitively know which tools to trigger or what actions to take.
            If the initial plan cannot be successfully executed, you should learn from previous execution results and revise your plan. The revised plan should be be under %s. Then use tools to follow the new plan.
            """,
            REPLANNING_TAG);

    String reasoningPreamble =
        """
            Below are the requirements for the reasoning:
            The reasoning makes a summary of the current trajectory based on the user query and tool outputs. Based on the tool outputs and plan, the reasoning also comes up with instructions to the next steps, making the trajectory closer to the final answer.
            """;

    String finalAnswerPreamble =
        """
            Below are the requirements for the final answer:
            The final answer should be precise and follow query formatting requirements. Some queries may not be answerable with the available tools and information. In those cases, inform the user why you cannot process their query and ask for more information.
            """;

    String toolCodePreamble =
        """
            Below are the requirements for the tool code:

            **Custom Tools:** The available tools are described in the context and can be directly used.
            - Code must be valid self-contained Python snippets with no imports and no references to tools or Python libraries that are not in the context.
            - You cannot use any parameters or fields that are not explicitly defined in the APIs in the context.
            - The code snippets should be readable, efficient, and directly relevant to the user query and reasoning steps.
            - When using the tools, you should use the library name together with the function name, e.g., vertex_search.search().
            - If Python libraries are not provided in the context, NEVER write your own code other than the function calls using the provided tools.
            """;

    String userInputPreamble =
        """
            VERY IMPORTANT instruction that you MUST follow in addition to the above instructions:

            You should ask for clarification if you need more information to answer the question.
            You should prefer using the information available in the context instead of repeated tool use.
            """;

    return String.join(
        "\n\n",
        highLevelPreamble,
        planningPreamble,
        reasoningPreamble,
        finalAnswerPreamble,
        toolCodePreamble,
        userInputPreamble);
  }
}
