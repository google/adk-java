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
package com.google.adk.plugins;

/**
 * Base class for creating plugins.
 *
 * <p>Plugins provide a structured way to intercept and modify agent, tool, and LLM behaviors at
 * critical execution points in a callback manner. While agent callbacks apply to a particular
 * agent, plugins applies globally to all agents added in the runner. Plugins are best used for
 * adding custom behaviors like logging, monitoring, caching, or modifying requests and responses at
 * key stages.
 *
 * <p>A plugin can implement one or more methods of callbacks, but should not implement the same
 * method of callback for multiple times.
 */
public abstract class BasePlugin implements Plugin {
  protected final String name;

  /**
   * Constructs a new plugin with the given name.
   *
   * @param name The name of the plugin.
   */
  public BasePlugin(String name) {
    this.name = name;
  }

  @Override
  public String getName() {
    return name;
  }
}
