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

package com.google.adk.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Command line application to deploy an ADK Java Agent to Vertex AI Agent Engine (Reasoning
 * Engine).
 */
class AgentEngineDeployer {
  private static final Logger logger = Logger.getLogger(AgentEngineDeployer.class.getName());

  private final String region;
  private final String projectId;
  private final String agentName;
  private final int serverPort;
  private final String sourceDir;
  private final Path tempDir;

  private AgentEngineDeployer(
      String region,
      String projectId,
      String agentName,
      int serverPort,
      String sourceDir,
      Path tempDir) {
    this.region = region;
    this.projectId = projectId;
    this.agentName = agentName;
    this.serverPort = serverPort;
    this.sourceDir = sourceDir;
    this.tempDir = tempDir;
  }

  /** Creates a temporary Dockerfile and bundles the application for Reasoning Engine deployment. */
  static Path prepareBundle(int serverPort) throws IOException {
    Path tempDir = Files.createTempDirectory("agentEngineDeploy");
    Path dockerfile = tempDir.resolve("Dockerfile");

    String dockerfileContent =
        String.format(
            "FROM eclipse-temurin:21-jdk\n"
                + "WORKDIR /app\n"
                + "COPY . .\n"
                + "RUN ./mvnw clean package -DskipTests\n"
                + "EXPOSE %d\n"
                + "CMD [\"java\", \"-jar\", \"target/app.jar\"]\n",
            serverPort);

    Files.writeString(dockerfile, dockerfileContent);
    logger.info("Prepared Dockerfile at " + dockerfile.toAbsolutePath());
    return tempDir;
  }

  /** Orchestrates the deployment process. */
  public void deploy() throws IOException {
    logger.info("Starting Agent Engine deployment...");
    logger.info(
        String.format(
            "Deploying Agent '%s' to project '%s' in region '%s'...",
            agentName, projectId, region));

    // TODO: Integrate with Vertex AI CreateReasoningEngine API client.
    logger.info("Preparation complete. Skipping actual deployment to Vertex AI for now.");
  }

  /** Builder for {@link AgentEngineDeployer}. */
  public static class Builder {
    private String region;
    private String projectId;
    private String agentName;
    private int serverPort;
    private String sourceDir;

    public Builder region(String region) {
      this.region = region;
      return this;
    }

    public Builder projectId(String projectId) {
      this.projectId = projectId;
      return this;
    }

    public Builder agentName(String agentName) {
      this.agentName = agentName;
      return this;
    }

    public Builder serverPort(int serverPort) {
      this.serverPort = serverPort;
      return this;
    }

    public Builder sourceDir(String sourceDir) {
      this.sourceDir = sourceDir;
      return this;
    }

    public AgentEngineDeployer build() throws IOException {
      if (projectId == null || projectId.isEmpty()) {
        throw new IllegalStateException("Project ID must be specified.");
      }
      if (agentName == null || agentName.isEmpty()) {
        agentName = "ADK Java Agent: " + Instant.now().toString();
      }
      if (sourceDir == null || sourceDir.isEmpty()) {
        sourceDir = System.getProperty("user.dir");
      }
      Path tempDir = AgentEngineDeployer.prepareBundle(serverPort);
      return new AgentEngineDeployer(region, projectId, agentName, serverPort, sourceDir, tempDir);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static void main(String[] args) {
    Builder builder = AgentEngineDeployer.builder().region("us-central1").serverPort(8080);

    // Minimal argument parsing logic
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--project":
          if (i + 1 < args.length) {
            builder.projectId(args[++i]);
          }
          break;
        case "--region":
          if (i + 1 < args.length) {
            builder.region(args[++i]);
          }
          break;
        case "--name":
          if (i + 1 < args.length) {
            builder.agentName(args[++i]);
          }
          break;
        case "--port":
          if (i + 1 < args.length) {
            builder.serverPort(Integer.parseInt(args[++i]));
          }
          break;
        case "--source-dir":
          if (i + 1 < args.length) {
            builder.sourceDir(args[++i]);
          }
          break;
        default:
          logger.warning("Unknown argument: " + args[i]);
      }
    }

    try {
      AgentEngineDeployer deployer = builder.build();
      deployer.deploy();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Deployment failed", e);
      System.exit(1);
    }
  }
}
