package com.google.adk.agents;

import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentConfigsProvider;
import com.google.adk.agents.code.CodeRootAgent;
import com.google.adk.agents.report.ReportRootAgent;
import com.google.adk.agents.traveler.TravelerRootAgent;
import com.google.adk.web.AdkWebServer;
import java.util.Map;

/*
 * run command:
 * cd contrib/multi-agent-patterns
 * mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.MultiAgentsSystem
 */
public class MultiAgentsSystem {

  public static void main(String[] agrs) {
    AgentConfigsProvider provider = new AgentConfigsProvider();
    Map<String, AgentConfig> configs = provider.get();
    AdkWebServer.start( //
        new CodeRootAgent(configs).get(), //
        new ReportRootAgent(configs).get(), //
        new TravelerRootAgent(configs).get() //
        );
  }
}
