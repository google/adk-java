/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.adk.web;

import com.google.adk.agents.BaseAgent;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Interface for loading agents to the ADK Web Server.
 *
 * <p>Users implement this interface to register their agents with ADK Web Server.
 *
 * <p><strong>Thread Safety:</strong> Implementation must be thread-safe as it will be used as
 * Spring singleton beans and accessed concurrently by multiple HTTP requests.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgentLoader implements AgentLoader {
 *   @Override
 *   public ImmutableList<String> listAgents() {
 *     return ImmutableList.of("chat_bot", "code_assistant");
 *   }
 *
 *   @Override
 *   public BaseAgent loadAgent(String name) {
 *     switch (name) {
 *       case "chat_bot": return createChatBot();
 *       case "code_assistant": return createCodeAssistant();
 *       default: throw new java.util.NoSuchElementException("Agent not found: " + name);
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Then use with Maven plugin:
 *
 * <pre>{@code
 * mvn google-adk:web -Dagents=com.acme.MyAgentLoader
 * }</pre>
 *
 * <p>For config-based agents, see {@code ConfigAgentLoader} in the maven plugin which provides
 * YAML-based agent loading with hot-reloading support.
 *
 * <p>Inspired by Python ADK's BaseAgentLoader abstract base class pattern.
 */
@ThreadSafe
public interface AgentLoader {

  /**
   * Returns a list of available agent names.
   *
   * @return ImmutableList of agent names. Must not return null - return an empty list if no agents
   *     are available.
   */
  @Nonnull
  ImmutableList<String> listAgents();

  /**
   * Loads the BaseAgent instance for the specified agent name.
   *
   * @param name the name of the agent to load
   * @return BaseAgent instance for the given name
   * @throws java.util.NoSuchElementException if the agent doesn't exist
   * @throws IllegalStateException if the agent exists but fails to load
   */
  BaseAgent loadAgent(String name);

  /**
   * Checks if an agent with the given name exists.
   *
   * <p>Default implementation checks if the name is in {@link #listAgents()}. Implementations may
   * override for more efficient lookup.
   *
   * @param name the name of the agent to check
   * @return true if the agent exists, false otherwise
   */
  default boolean hasAgent(String name) {
    return listAgents().contains(name);
  }

  /**
   * Removes an agent from the cache, forcing it to be reloaded on next access.
   *
   * <p>This is inspired by Python ADK's remove_agent_from_cache pattern. Default implementation
   * does nothing as not all loaders support caching.
   *
   * @param name the name of the agent to remove from cache
   * @return true if the agent was in cache and removed, false otherwise
   */
  default boolean removeAgentFromCache(String name) {
    // Default implementation does nothing - override in caching implementations
    return false;
  }

  /**
   * Stops the agent loader and releases any resources.
   *
   * <p>Default implementation does nothing. Override in implementations that need cleanup (e.g.,
   * stopping file watchers).
   */
  default void stop() {
    // Default implementation does nothing
  }
}
