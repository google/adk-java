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

import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.CassandraArtifactService;
import com.google.adk.memory.CassandraMemoryService;
import com.google.adk.memory.RedbusEmbeddingService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.CassandraSessionService;
import com.google.adk.store.CassandraHelper;
import com.google.common.collect.ImmutableList;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * The class for the Cassandra-backed GenAi runner.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-19
 */
public class CassandraRunner extends Runner {

  public CassandraRunner(BaseAgent agent) {
    this(
        agent,
        agent.name(),
        ImmutableList.of(),
        new CqlSessionBuilder()
            .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
            .withLocalDatacenter("datacenter1"));
  }

  /**
   * Initializes the runner with a connection to a local Cassandra instance.
   *
   * @param agent the agent to run
   * @param appName the name of the application
   */
  public CassandraRunner(BaseAgent agent, String appName) {
    this(
        agent,
        appName,
        ImmutableList.of(),
        new CqlSessionBuilder()
            .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
            .withLocalDatacenter("datacenter1"));
  }

  /**
   * Initializes the runner with a connection to a local Cassandra instance.
   *
   * @param agent the agent to run
   * @param appName the name of the application
   * @param plugins the list of plugins to use
   */
  public CassandraRunner(BaseAgent agent, String appName, List<BasePlugin> plugins) {
    this(
        agent,
        appName,
        plugins,
        new CqlSessionBuilder()
            .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
            .withLocalDatacenter("datacenter1"));
  }

  /**
   * Initializes the runner with a custom Cassandra session builder.
   *
   * @param agent the agent to run
   * @param appName the name of the application
   * @param plugins the list of plugins to use
   * @param sessionBuilder the Cassandra session builder to use
   */
  public CassandraRunner(
      BaseAgent agent, String appName, List<BasePlugin> plugins, CqlSessionBuilder sessionBuilder) {
    super(
        agent,
        appName,
        initArtifactService(sessionBuilder),
        new CassandraSessionService(),
        new CassandraMemoryService(
            CassandraHelper.getSession(),
            "rae",
            "rae_data",
            new RedbusEmbeddingService(
                System.getenv("ADU") != null ? System.getenv("ADU") : "",
                System.getenv("ADP") != null ? System.getenv("ADP") : "")),
        plugins);
  }

  private static CassandraArtifactService initArtifactService(CqlSessionBuilder sessionBuilder) {
    CassandraHelper.initialize(sessionBuilder);
    return new CassandraArtifactService();
  }
}
