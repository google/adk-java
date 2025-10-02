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
package com.google.adk.models.springai.ollama;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

public class OllamaTestContainer {

  private static final String OLLAMA_IMAGE = "ollama/ollama:0.4.0";
  private static final int OLLAMA_PORT = 11434;
  private static final String MODEL_NAME = "llama3.2:1b";

  private final GenericContainer<?> container;

  public OllamaTestContainer() {
    this.container =
        new GenericContainer<>(DockerImageName.parse(OLLAMA_IMAGE))
            .withExposedPorts(OLLAMA_PORT)
            .withCommand("serve")
            .waitingFor(
                new HttpWaitStrategy()
                    .forPath("/api/version")
                    .forPort(OLLAMA_PORT)
                    .withStartupTimeout(Duration.ofMinutes(5)));
  }

  public void start() {
    container.start();
    pullModel();
  }

  public void stop() {
    if (container.isRunning()) {
      container.stop();
    }
  }

  public String getBaseUrl() {
    return "http://" + container.getHost() + ":" + container.getMappedPort(OLLAMA_PORT);
  }

  public String getModelName() {
    return MODEL_NAME;
  }

  private void pullModel() {
    try {
      org.testcontainers.containers.Container.ExecResult result =
          container.execInContainer("ollama", "pull", MODEL_NAME);

      if (result.getExitCode() != 0) {
        throw new RuntimeException(
            "Failed to pull model " + MODEL_NAME + ": " + result.getStderr());
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to pull model " + MODEL_NAME, e);
    }
  }

  public boolean isHealthy() {
    try {
      org.testcontainers.containers.Container.ExecResult result =
          container.execInContainer(
              "curl", "-f", "http://localhost:" + OLLAMA_PORT + "/api/version");

      return result.getExitCode() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
