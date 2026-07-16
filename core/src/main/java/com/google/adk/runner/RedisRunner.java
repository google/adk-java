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

package com.google.adk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.RedisArtifactService;
import com.google.adk.memory.RedisMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.RedisSessionService;
import com.google.adk.store.RedisHelper;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * The class for the Redis-backed GenAi runner.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public class RedisRunner extends Runner {

  /**
   * Initializes the runner with a connection to a local Redis instance.
   *
   * @param agent the agent to run
   */
  public RedisRunner(BaseAgent agent) {
    this(agent, agent.name(), ImmutableList.of(), "redis://localhost:6379");
  }

  /**
   * Initializes the runner with a connection to a local Redis instance.
   *
   * @param agent the agent to run
   * @param appName the name of the application
   */
  public RedisRunner(BaseAgent agent, String appName) {
    this(agent, appName, ImmutableList.of(), "redis://localhost:6379");
  }

  /**
   * Initializes the runner with a connection to a local Redis instance.
   *
   * @param agent the agent to run
   * @param appName the name of the application
   * @param plugins the list of plugins to use
   */
  public RedisRunner(BaseAgent agent, String appName, List<BasePlugin> plugins) {
    this(agent, appName, plugins, "redis://localhost:6379");
  }

  /**
   * Initializes the runner with a custom Redis URI.
   *
   * @param agent the agent to run
   * @param appName the name of the application
   * @param plugins the list of plugins to use
   * @param redisUri the Redis URI to use
   */
  public RedisRunner(BaseAgent agent, String appName, List<BasePlugin> plugins, String redisUri) {
    super(
        agent,
        appName,
        initArtifactService(redisUri),
        new RedisSessionService(),
        new RedisMemoryService(),
        plugins);
  }

  private static RedisArtifactService initArtifactService(String redisUri) {
    RedisHelper.initialize(redisUri);
    return new RedisArtifactService();
  }
}
