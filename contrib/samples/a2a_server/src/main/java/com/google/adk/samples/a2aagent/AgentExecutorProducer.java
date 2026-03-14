package com.google.adk.samples.a2aagent;

import com.google.adk.a2a.executor.AgentExecutorConfig;
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
    return new com.google.adk.a2a.executor.AgentExecutor.Builder()
        .agent(Agent.ROOT_AGENT)
        .appName(appName)
        .sessionService(sessionService)
        .agentExecutorConfig(AgentExecutorConfig.builder().build())
        .build();
  }
}
