package com.google.adk.agents.report;

import com.google.adk.agents.base.AgentConfigsProvider;
import com.google.adk.web.AdkWebServer;

/*
 * run command:
 * cd contrib/multi-agent-patterns
 * mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.report.ReportWriterApp
 */

public final class ReportWriterApp {

  public static void main(String[] agrs) {
    ReportRootAgent root = new ReportRootAgent(new AgentConfigsProvider());
    AdkWebServer.start(root.get());
  }
}
