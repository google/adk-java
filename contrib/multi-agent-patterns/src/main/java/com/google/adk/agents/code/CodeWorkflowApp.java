package com.google.adk.agents.code;

import com.google.adk.agents.base.AgentConfigsProvider;
import com.google.adk.web.AdkWebServer;

/*
 * run command:
 * cd contrib/multi-agent-patterns
 * mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.code.CodeWorkflowApp
 */

public final class CodeWorkflowApp {

  public static void main(String[] agrs) {
    AgentConfigsProvider configs = new AgentConfigsProvider();
    CodeRootAgent root = new CodeRootAgent(configs);
    AdkWebServer.start(root.get());
  }
}
