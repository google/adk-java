package com.google.adk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.PostgresArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.PostgresSessionService;
import java.io.IOException;
import java.sql.SQLException;

/**
 * @author Arun Parmar
 * @author Yashas Shetty (Modified to use shared table)
 * @param agent The agent to run
 * @throws IOException if initialization fails
 * @throws SQLException if database connection fails
 */
public class PostgresRunner extends Runner {
  public PostgresRunner(BaseAgent agent) throws IOException, SQLException {
    this(agent, /* appName= */ agent.name());
  }

  /**
   * Creates PostgresRunner with custom app name. Uses shared "artifacts" table for all
   * applications.
   *
   * @param agent The agent to run
   * @param appName Application name for namespacing
   * @throws IOException if initialization fails
   * @throws SQLException if database connection fails
   */
  public PostgresRunner(BaseAgent agent, String appName) throws IOException, SQLException {
    super(
        agent,
        appName,
        new PostgresArtifactService(), // Uses default "artifacts" table
        new PostgresSessionService(),
        new InMemoryMemoryService());
  }

  /**
   * Creates PostgresRunner with custom app name and memory service. Uses shared "artifacts" table
   * for all applications.
   *
   * @param agent The agent to run
   * @param appName Application name for namespacing
   * @param memoryService Custom memory service implementation
   * @throws IOException if initialization fails
   * @throws SQLException if database connection fails
   */
  public PostgresRunner(BaseAgent agent, String appName, BaseMemoryService memoryService)
      throws IOException, SQLException {
    super(
        agent,
        appName,
        new PostgresArtifactService(), // Uses default "artifacts" table
        new PostgresSessionService(),
        memoryService);
  }
}
