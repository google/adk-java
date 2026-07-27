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
package com.example.springbootadktemplate;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

  private final Runner runner;
  private final LlmAgent agent;

  public AgentService(Runner runner, LlmAgent agent) {
    this.runner = runner;
    this.agent = agent;
  }

  public String getAgentInfo() {
    return "Agent created: "
        + agent.getClass().getSimpleName()
        + ", Runner: "
        + runner.getClass().getSimpleName();
  }
}
