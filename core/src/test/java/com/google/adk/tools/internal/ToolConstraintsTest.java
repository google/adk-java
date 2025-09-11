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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BuiltInTool;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for ToolConstraints guardrails (pure white-box, no BaseTool dependency). */
final class ToolConstraintsTest {

  // only rely on the BuiltInTool marker interface to distinguish whether it is built-in
  static final class FakeBuiltinTool implements BuiltInTool {}

  static final class FakeNormalTool {}

  @SuppressWarnings("unchecked")
  private static LlmAgent mockAgent(
      String name, BaseAgent parent, List<?> tools, List<BaseAgent> subs) {

    LlmAgent a = mock(LlmAgent.class);

    when(a.name()).thenReturn(name);

    when(a.parentAgent()).thenReturn(parent);
    when(a.subAgents()).thenReturn((List) subs);
    when(a.tools()).thenReturn((List) tools);

    return a;
  }

  @Test
  void singleBuiltin_only_passes() {
    LlmAgent root = mockAgent("root", null, List.of(new FakeBuiltinTool()), List.of());
    assertDoesNotThrow(() -> ToolConstraints.validateAgentTree(root));
  }

  @Test
  void builtin_plus_normal_throws() {
    LlmAgent root =
        mockAgent("root", null, List.of(new FakeBuiltinTool(), new FakeNormalTool()), List.of());
    assertThrows(IllegalStateException.class, () -> ToolConstraints.validateAgentTree(root));
  }

  @Test
  void two_builtins_throws() {
    LlmAgent root =
        mockAgent("root", null, List.of(new FakeBuiltinTool(), new FakeBuiltinTool()), List.of());
    assertThrows(IllegalStateException.class, () -> ToolConstraints.validateAgentTree(root));
  }

  @Test
  void builtin_in_subAgent_throws() {
    LlmAgent child =
        mockAgent("child", mock(LlmAgent.class), List.of(new FakeBuiltinTool()), List.of());
    LlmAgent root = mockAgent("root", null, List.of(new FakeNormalTool()), List.of(child));
    assertThrows(IllegalStateException.class, () -> ToolConstraints.validateAgentTree(root));
  }

  @Test
  void only_normal_tools_in_tree_passes() {
    LlmAgent child =
        mockAgent("child", mock(LlmAgent.class), List.of(new FakeNormalTool()), List.of());
    LlmAgent root = mockAgent("root", null, List.of(new FakeNormalTool()), List.of(child));
    assertDoesNotThrow(() -> ToolConstraints.validateAgentTree(root));
  }
}
