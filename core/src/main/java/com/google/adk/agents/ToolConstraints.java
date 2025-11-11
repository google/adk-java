/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.agents;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BuiltInTool;

// 导入 Guava 的 ImmutableList
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 在 Agent 构建期校验工具组合的合法性 (A1/A2 约束)。
 * (V3 - 修正了泛型通配符错误)
 */
public final class ToolConstraints {

    public static void validate(BaseAgent rootAgent) {
        if (rootAgent == null) {
            return;
        }
        validateAgentTree(rootAgent, true);
    }

    /**
     * 递归辅助方法，遍历 Agent 树并检查每个节点的约束。
     */
    private static void validateAgentTree(BaseAgent agent, boolean isRoot) {
        // 我们只关心 LlmAgent
        if (agent instanceof LlmAgent) {
            LlmAgent llmAgent = (LlmAgent) agent;

            // 1. 获取当前 Agent 的所有工具
            List<BaseTool> allTools = llmAgent.tools();

            // 2. 将工具分为“内置”和“普通”
            List<BaseTool> builtInTools = allTools.stream()
                    .filter(tool -> tool instanceof BuiltInTool)
                    .collect(Collectors.toList());

            List<BaseTool> regularTools = allTools.stream()
                    .filter(tool -> !(tool instanceof BuiltInTool))
                    .collect(Collectors.toList());

            boolean hasBuiltInTools = !builtInTools.isEmpty();
            boolean hasRegularTools = !regularTools.isEmpty();

            // 3. 执行 A1 约束检查
            if (hasBuiltInTools) {
                if (hasRegularTools) {
                    throw new ToolConstraintViolationException(
                            String.format(
                                    "Agent '%s' 违反 A1 约束：内置工具 (BuiltInTool) 不能与普通工具 (Regular Tool) 混用。请将它们放在不同的 Agent 中。",
                                    agent.name()
                            )
                    );
                }

                if (builtInTools.size() > 1) {
                    throw new ToolConstraintViolationException(
                            String.format(
                                    "Agent '%s' 违反 A1 约束：一个 Agent 最多只能有一个内置工具，但找到了 %d 个。",
                                    agent.name(), builtInTools.size()
                            )
                    );
                }
            }

            // 4. 执行 A2 约束检查
            if (hasBuiltInTools && !isRoot) {
                throw new ToolConstraintViolationException(
                        String.format(
                                "Agent '%s' 违反 A2 约束：内置工具 (BuiltInTool) 只能在根 Agent (Root Agent) 上定义。子 Agent 禁止使用内置工具。",
                                agent.name()
                        )
                );
            }
        }

        // 5. 递归遍历所有子 Agent
        // 【关键修复】: 使用通配符 List<?> 来接收 subAgents() 的返回值，这是正确的 Java 写法。
        List<? extends BaseAgent> subAgents = agent.subAgents();
        if (subAgents != null) {
            for (BaseAgent subAgent : subAgents) { // 遍历时使用 BaseAgent 是安全的
                // 递归调用
                validateAgentTree(subAgent, false);
            }
        }
    }

    private ToolConstraints() {}
}