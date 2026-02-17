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
