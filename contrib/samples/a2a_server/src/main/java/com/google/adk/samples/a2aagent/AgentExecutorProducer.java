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

package com.google.adk.samples.a2aagent;

import com.google.adk.a2a.executor.AgentExecutorConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.samples.a2aagent.agent.Agent;
import com.google.adk.sessions.InMemorySessionService;
import io.a2a.server.agentexecution.AgentExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Produces the {@link AgentExecutor} instance that handles agent interactions. */
@ApplicationScoped
public class AgentExecutorProducer {

  @ConfigProperty(name = "my.adk.app.name", defaultValue = "default-app")
  String appName;

  @Produces
  public AgentExecutor agentExecutor() {
    InMemorySessionService sessionService = new InMemorySessionService();
    InMemoryArtifactService artifactService = new InMemoryArtifactService();
    return new com.google.adk.a2a.executor.AgentExecutor.Builder()
        .agent(Agent.ROOT_AGENT)
        .appName(appName)
        .sessionService(sessionService)
        .artifactService(artifactService)
        .agentExecutorConfig(AgentExecutorConfig.builder().build())
        .build();
  }
}
