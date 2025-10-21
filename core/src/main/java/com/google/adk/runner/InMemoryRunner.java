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

import com.google.adk.Telemetry;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import io.opentelemetry.api.trace.Tracer;
import java.util.List;

/** The class for the in-memory GenAi runner, using in-memory artifact and session services. */
public class InMemoryRunner extends Runner {

  public InMemoryRunner(BaseAgent agent) {
    // TODO: Change the default appName to InMemoryRunner to align with adk python.
    // Check the dev UI in case we break something there.
    this(agent, /* appName= */ agent.name(), ImmutableList.of(), Telemetry.getTracer());
  }

  public InMemoryRunner(BaseAgent agent, String appName) {
    this(agent, appName, ImmutableList.of(), Telemetry.getTracer());
  }

  public InMemoryRunner(BaseAgent agent, String appName, List<BasePlugin> plugins) {
    this(agent, appName, plugins, Telemetry.getTracer());
  }

  public InMemoryRunner(BaseAgent agent, String appName, List<BasePlugin> plugins, Tracer tracer) {
    super(
        agent,
        appName,
        new InMemoryArtifactService(),
        new InMemorySessionService(),
        new InMemoryMemoryService(),
        plugins,
        tracer);
  }
}
