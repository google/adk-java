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
package com.google.adk.tools.internal;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BuiltInTool;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Guardrails for built-in tools usage. */
public final class ToolConstraints {

  private static final String DOC =
      "See docs: https://google.github.io/adk-docs/tools/built-in-tools/#limitations";

  private ToolConstraints() {}

  /** Public entry: validate the whole agent subtree. */
  public static void validateAgentTree(BaseAgent agent) {
    Objects.requireNonNull(agent, "agent");
    if (!(agent instanceof LlmAgent llm)) {
      // 仅针对 LlmAgent 的工具组合做校验
      return;
    }
    validateSingleAgent(llm);
    for (BaseAgent child : subAgentsCompat(llm)) {
      validateAgentTree(child);
    }
  }

  /** Validate constraints on a single LlmAgent. */
  private static void validateSingleAgent(LlmAgent agent) {
    List<Object> tools = new ArrayList<>(declaredToolsCompat(agent));

    long builtinCount = tools.stream().filter(t -> t instanceof BuiltInTool).count();
    boolean hasBuiltin = builtinCount > 0;
    boolean hasOthers = tools.stream().anyMatch(t -> !(t instanceof BuiltInTool));

    // A1: Only one built-in tool is allowed in a single Agent, and it cannot coexist with other
    // tools.
    if (builtinCount > 1 || (hasBuiltin && hasOthers)) {
      throw new IllegalStateException(errorMixedBuiltins(agent.name(), tools));
    }

    // A2: Subagents are not allowed to use built-in tools
    if (hasBuiltin && parentAgentCompat(agent) != null) {
      throw new IllegalStateException(errorBuiltinInSubAgent(agent.name()));
    }
  }

  // ---------- Friendly error messages ----------

  private static String errorMixedBuiltins(String agentName, List<Object> tools) {
    String toolNames =
        tools.stream()
            .map(t -> t == null ? "null" : t.getClass().getSimpleName())
            .collect(Collectors.joining(", "));
    return "[Built-in tools limitation violated] Agent `"
        + agentName
        + "` has invalid tool mix: "
        + toolNames
        + ". Only one built-in tool allowed, and it cannot be used with other tools. "
        + DOC;
  }

  private static String errorBuiltinInSubAgent(String agentName) {
    return "[Built-in tools limitation violated] Sub-agent `"
        + agentName
        + "` cannot use a built-in tool. Built-ins must be used only at the root/single agent. "
        + DOC;
  }

  // ---------- Compatibility helpers (avoid hard-coding method names) ----------

  /** Return the declared tools list using best-effort method discovery. */
  @SuppressWarnings("unchecked")
  private static List<Object> declaredToolsCompat(LlmAgent agent) {
    // Priority：tools() → declaredTools() → getTools()
    for (String m : new String[] {"tools", "declaredTools", "getTools"}) {
      try {
        var mh =
            MethodHandles.publicLookup()
                .findVirtual(agent.getClass(), m, MethodType.methodType(List.class));
        Object result = mh.invoke(agent);
        if (result instanceof List<?>) {
          return new ArrayList<>((List<?>) result);
        }
      } catch (Throwable ignore) {
        // try next
      }
    }
    // Returns empty if not found
    return Collections.emptyList();
  }

  /** Return sub agents using best-effort method discovery. */
  @SuppressWarnings("unchecked")
  private static List<BaseAgent> subAgentsCompat(LlmAgent agent) {
    for (String m : new String[] {"subAgents", "children", "getSubAgents"}) {
      try {
        var mh =
            MethodHandles.publicLookup()
                .findVirtual(agent.getClass(), m, MethodType.methodType(List.class));
        Object result = mh.invoke(agent);
        if (result instanceof List<?>) {
          List<?> raw = (List<?>) result;
          List<BaseAgent> safe =
              raw.stream().filter(e -> e instanceof BaseAgent).map(e -> (BaseAgent) e).toList();
          return new ArrayList<>(safe);
        }
      } catch (Throwable ignore) {
        // try next
      }
    }
    return Collections.emptyList();
  }

  /** Return parent agent if available. */
  private static BaseAgent parentAgentCompat(LlmAgent agent) {
    for (String m : new String[] {"parentAgent", "getParent", "getParentAgent"}) {
      try {
        var mh =
            MethodHandles.publicLookup()
                .findVirtual(agent.getClass(), m, MethodType.methodType(BaseAgent.class));
        Object result = mh.invoke(agent);
        return (result instanceof BaseAgent) ? (BaseAgent) result : null;
      } catch (Throwable ignore) {
        // try next
      }
    }
    return null;
  }
}
