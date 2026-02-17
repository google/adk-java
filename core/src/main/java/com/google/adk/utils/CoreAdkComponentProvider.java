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
package com.google.adk.utils;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ExampleTool;
import com.google.adk.tools.ExitLoopTool;
import com.google.adk.tools.LoadArtifactsTool;
import com.google.adk.tools.LongRunningFunctionTool;
import com.google.adk.tools.UrlContextTool;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provides ADK components that are part of core. */
public class CoreAdkComponentProvider implements AdkComponentProvider {

  /**
   * Returns agent classes for {@link LlmAgent}, {@link LoopAgent}, {@link ParallelAgent} and {@link
   * SequentialAgent}.
   *
   * @return a list of agent classes.
   */
  @Override
  public List<Class<? extends BaseAgent>> getAgentClasses() {
    return Arrays.asList(
        LlmAgent.class, LoopAgent.class, ParallelAgent.class, SequentialAgent.class);
  }

  /**
   * Returns tool classes for {@link AgentTool}, {@link LongRunningFunctionTool} and {@link
   * ExampleTool}.
   *
   * @return a list of tool classes.
   */
  @Override
  public List<Class<? extends BaseTool>> getToolClasses() {
    return Arrays.asList(AgentTool.class, LongRunningFunctionTool.class, ExampleTool.class);
  }

  /**
   * Returns tool instances for {@link LoadArtifactsTool}, {@link ExitLoopTool} and {@link
   * UrlContextTool}.
   *
   * @return a map of tool instances.
   */
  @Override
  public Map<String, BaseTool> getToolInstances() {
    Map<String, BaseTool> toolInstances = new HashMap<>();
    toolInstances.put("load_artifacts", LoadArtifactsTool.INSTANCE);
    toolInstances.put("exit_loop", ExitLoopTool.INSTANCE);
    toolInstances.put("url_context", UrlContextTool.INSTANCE);
    return toolInstances;
  }
}
