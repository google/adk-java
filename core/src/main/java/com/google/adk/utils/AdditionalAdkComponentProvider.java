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

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.GoogleMapsTool;
import com.google.adk.tools.GoogleSearchTool;
import com.google.adk.tools.mcp.McpToolset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provides ADK components that are part of core. */
public final class AdditionalAdkComponentProvider implements AdkComponentProvider {

  /**
   * Returns tool instances for {@link GoogleSearchTool} and {@link GoogleMapsTool}.
   *
   * @return a map of tool instances.
   */
  @Override
  public Map<String, BaseTool> getToolInstances() {
    Map<String, BaseTool> toolInstances = new HashMap<>();
    toolInstances.put("google_search", GoogleSearchTool.INSTANCE);
    toolInstances.put("google_maps_grounding", GoogleMapsTool.INSTANCE);
    return toolInstances;
  }

  /**
   * Returns toolset classes for {@link McpToolset}.
   *
   * @return a list of toolset classes.
   */
  @Override
  public List<Class<? extends BaseToolset>> getToolsetClasses() {
    return Arrays.asList(McpToolset.class);
  }
}
