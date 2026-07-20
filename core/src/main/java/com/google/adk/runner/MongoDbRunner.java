package com.google.adk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.MongoDbArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.MongoDbSessionService;
import java.io.IOException;

/**
 * @author Harshavardhan A
 */
public class MongoDbRunner extends Runner {

  public MongoDbRunner(BaseAgent agent) throws IOException {
    this(agent, agent.name());
  }

  public MongoDbRunner(BaseAgent agent, String appName) throws IOException {
    super(
        agent,
        appName,
        new MongoDbArtifactService(),
        new MongoDbSessionService(),
        new InMemoryMemoryService());
  }
}
