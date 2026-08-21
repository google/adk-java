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
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;

/** Service provider interface for ADK components to be registered in {@link ComponentRegistry}. */
public interface AdkComponentProvider {

  /**
   * Returns a list of agent classes to register.
   *
   * @return a list of agent classes.
   */
  default List<Class<? extends BaseAgent>> getAgentClasses() {
    return ImmutableList.of();
  }

  /**
   * Returns a list of tool classes to register.
   *
   * @return a list of tool classes.
   */
  default List<Class<? extends BaseTool>> getToolClasses() {
    return ImmutableList.of();
  }

  /**
   * Returns a list of toolset classes to register.
   *
   * @return a list of toolset classes.
   */
  default List<Class<? extends BaseToolset>> getToolsetClasses() {
    return ImmutableList.of();
  }

  /**
   * Returns a map of tool instances to register, with tool name as key.
   *
   * @return a map of tool instances.
   */
  default Map<String, BaseTool> getToolInstances() {
    return ImmutableMap.of();
  }
}
