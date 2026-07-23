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

package com.google.adk.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import org.junit.jupiter.api.Test;

public class AgentStaticLoaderTest {

  @Test
  public void testAgentStaticLoaderApproach() {
    BaseAgent testAgent =
        LlmAgent.builder()
            .name("test_agent")
            .model("gemini-2.0-flash-lite")
            .description("Test agent for demonstrating AgentStaticLoader")
            .instruction("You are a test agent.")
            .build();

    AgentStaticLoader staticLoader = new AgentStaticLoader(testAgent);

    assertTrue(staticLoader.listAgents().contains("test_agent"));
    assertEquals(testAgent, staticLoader.loadAgent("test_agent"));
    assertEquals("test_agent", staticLoader.loadAgent("test_agent").name());
  }
}
