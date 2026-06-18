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

package com.google.adk.planner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Planner;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A planner that uses an LLM to dynamically decide which sub-agent(s) to run next.
 *
 * <p>The LLM is given a system prompt describing the available agents and their descriptions, the
 * current state, and recent events. It responds with the agent name(s) to run, "DONE", or "DONE:
 * summary".
 */
public final class SupervisorPlanner implements Planner {

  private static final Logger logger = LoggerFactory.getLogger(SupervisorPlanner.class);

  private static final int DEFAULT_MAX_EVENTS = 20;

  private final BaseLlm llm;
  private final Optional<String> systemInstruction;
  private final int maxEvents;
  private final List<String> decisionHistory = new ArrayList<>();

  public SupervisorPlanner(BaseLlm llm, String systemInstruction, int maxEvents) {
    this.llm = llm;
    this.systemInstruction = Optional.ofNullable(systemInstruction);
    this.maxEvents = maxEvents;
  }

  public SupervisorPlanner(BaseLlm llm, String systemInstruction) {
    this(llm, systemInstruction, DEFAULT_MAX_EVENTS);
  }

  public SupervisorPlanner(BaseLlm llm) {
    this(llm, null, DEFAULT_MAX_EVENTS);
  }

  @Override
  public Single<PlannerAction> firstAction(PlanningContext context) {
    return askLlm(context);
  }

  @Override
  public Single<PlannerAction> nextAction(PlanningContext context) {
    return askLlm(context);
  }

  private Single<PlannerAction> askLlm(PlanningContext context) {
    String prompt = buildPrompt(context);
    LlmRequest.Builder requestBuilder =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.builder().role("user").parts(Part.fromText(prompt)).build()));
    systemInstruction.ifPresent(
        si ->
            requestBuilder.config(
                GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(si)))
                    .build()));
    LlmRequest request = requestBuilder.build();

    return llm.generateContent(request, false)
        .lastOrError()
        .map(
            response -> {
              String text = extractText(response);
              PlannerAction action = parseResponse(text, context);
              recordDecision(action);
              return action;
            })
        .onErrorReturn(
            error -> {
              logger.warn("LLM call failed in SupervisorPlanner, returning Done", error);
              return new PlannerAction.Done();
            });
  }

  private String buildPrompt(PlanningContext context) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are a supervisor deciding which agent to run next.\n\n");
    sb.append("Available agents:\n");
    for (BaseAgent agent : context.availableAgents()) {
      sb.append("- ").append(agent.name()).append(": ").append(agent.description()).append("\n");
    }
    sb.append("\nCurrent state keys: ").append(context.state().keySet()).append("\n");

    List<Event> events = context.events();
    if (!events.isEmpty()) {
      sb.append("\nRecent events:\n");
      int start = Math.max(0, events.size() - maxEvents);
      for (int i = start; i < events.size(); i++) {
        Event event = events.get(i);
        sb.append("- ")
            .append(event.author())
            .append(": ")
            .append(event.stringifyContent())
            .append("\n");
      }
    }

    if (!decisionHistory.isEmpty()) {
      sb.append("\nPrevious decisions (in order):\n");
      for (int i = 0; i < decisionHistory.size(); i++) {
        sb.append(i + 1).append(". ").append(decisionHistory.get(i)).append("\n");
      }
    }

    context
        .userContent()
        .ifPresent(
            content -> sb.append("\nOriginal user request: ").append(content.text()).append("\n"));

    sb.append(
        "\nRespond with exactly one of:\n"
            + "- The name of the agent to run next\n"
            + "- Multiple agent names separated by commas (to run in parallel)\n"
            + "- DONE (if the task is complete)\n"
            + "- DONE: <summary> (if complete with a summary)\n"
            + "\nRespond with only the agent name(s) or DONE, nothing else.");
    return sb.toString();
  }

  private String extractText(LlmResponse response) {
    return response.content().flatMap(Content::parts).stream()
        .flatMap(List::stream)
        .flatMap(part -> part.text().stream())
        .collect(Collectors.joining())
        .trim();
  }

  private PlannerAction parseResponse(String text, PlanningContext context) {
    if (text.isEmpty()) {
      return new PlannerAction.Done();
    }

    String upper = text.toUpperCase().trim();
    if (upper.equals("DONE")) {
      return new PlannerAction.Done();
    }
    if (upper.startsWith("DONE:")) {
      String summary = text.substring(text.indexOf(':') + 1).trim();
      return new PlannerAction.DoneWithResult(summary);
    }

    // Try to parse as agent name(s)
    String[] parts = text.split(",");
    ImmutableList.Builder<BaseAgent> agentsBuilder = ImmutableList.builder();
    for (String part : parts) {
      String agentName = part.trim();
      try {
        agentsBuilder.add(context.findAgent(agentName));
      } catch (IllegalArgumentException e) {
        logger.warn("LLM returned unknown agent name '{}', treating as Done", agentName);
        return new PlannerAction.Done();
      }
    }
    ImmutableList<BaseAgent> agents = agentsBuilder.build();
    if (agents.isEmpty()) {
      return new PlannerAction.Done();
    }
    return new PlannerAction.RunAgents(agents);
  }

  private void recordDecision(PlannerAction action) {
    if (action instanceof PlannerAction.RunAgents run) {
      decisionHistory.add(
          "Run: " + run.agents().stream().map(BaseAgent::name).collect(Collectors.joining(", ")));
    } else if (action instanceof PlannerAction.DoneWithResult done) {
      decisionHistory.add("Done: " + done.result());
    } else if (action instanceof PlannerAction.Done) {
      decisionHistory.add("Done");
    }
  }
}
