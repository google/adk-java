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

package com.google.adk.web.testing;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import java.util.List;

/** Graphs shared by the graph tests. */
public final class GraphFixtures {

  /**
   * A support desk: {@code classify} routes to a billing agent with a tool, a nested {@code
   * tech_flow} workflow, or a default fallback, and every branch ends in {@code summarize}.
   */
  public static Workflow supportWorkflow() {
    Node classify = step("classify");
    BaseAgent billing = llmAgent("billing_agent", List.of(tool("lookup_invoice")), List.of());
    Node diagnose = step("diagnose");
    Workflow techFlow =
        Workflow.builder()
            .name("tech_flow")
            .edges(new Edge(Start.INSTANCE, diagnose), new Edge(diagnose, step("fix")))
            .build();
    Node fallback = step("fallback");
    Node summarize = step("summarize");
    return Workflow.builder()
        .name("support")
        .edges(
            new Edge(Start.INSTANCE, classify),
            new Edge(classify, billing, new Route.Tag("billing")),
            new Edge(classify, techFlow, new Route.Tag("tech")),
            new Edge(classify, fallback, Route.Default.INSTANCE),
            new Edge(billing, summarize),
            new Edge(techFlow, summarize),
            new Edge(fallback, summarize))
        .build();
  }

  /** An agent tree: {@code assistant} delegates to {@code helper}, which has a tool. */
  public static BaseAgent assistantAgent() {
    return llmAgent(
        "assistant",
        List.of(),
        List.of(llmAgent("helper", List.of(tool("lookup_invoice")), List.of())));
  }

  public static Node step(String name) {
    return new StepNode(name);
  }

  /** An agent with {@code tools}, which may hold tools and toolsets, and {@code subAgents}. */
  public static LlmAgent llmAgent(String name, List<?> tools, List<? extends BaseAgent> subAgents) {
    return LlmAgent.builder()
        .name(name)
        .model("gemini-2.5-flash")
        .tools(tools)
        .subAgents(subAgents)
        .build();
  }

  public static BaseTool tool(String name) {
    return new BaseTool(name, "test tool") {};
  }

  private GraphFixtures() {}
}
