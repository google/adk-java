/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not in compliance with the License.
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

package com.google.adk.codeexecutors;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A code executor that uses a custom container to execute code.
 *
 * <p>This implementation uses blocking I/O to interact with Docker via {@code docker-java} library.
 */
public class ContainerCodeExecutor extends BaseCodeExecutor {
  private static final Logger logger = LoggerFactory.getLogger(ContainerCodeExecutor.class);
  private static final String DEFAULT_IMAGE_TAG = "adk-code-executor:latest";

  private final Optional<String> baseUrl;
  private final String image;
  private final Optional<String> dockerPath;
  private final DockerClient dockerClient;
  private Container container;

  /**
   * Initializes the ContainerCodeExecutor.
   *
   * @param baseUrl Optional. The base url of the user hosted Docker client.
   * @param image The tag of the predefined image or custom image to run on the container. Either
   *     dockerPath or image must be set.
   * @param dockerPath The path to the directory containing the Dockerfile. If set, build the image
   *     from the dockerfile path instead of using the predefined image. Either dockerPath or image
   *     must be set.
   */
  public ContainerCodeExecutor(
      Optional<String> baseUrl, Optional<String> image, Optional<String> dockerPath) {
    if (image.isEmpty() && dockerPath.isEmpty()) {
      throw new IllegalArgumentException(
          "Either image or dockerPath must be set for ContainerCodeExecutor.");
    }
    this.baseUrl = baseUrl;
    this.image = image.orElse(DEFAULT_IMAGE_TAG);
    this.dockerPath = dockerPath.map(p -> Paths.get(p).toAbsolutePath().toString());

    if (baseUrl.isPresent()) {
      var config =
          DefaultDockerClientConfig.createDefaultConfigBuilder()
              .withDockerHost(baseUrl.get())
              .build();
      this.dockerClient = DockerClientBuilder.getInstance(config).build();
    } else {
      this.dockerClient = DockerClientBuilder.getInstance().build();
    }

    initContainer();
    Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupContainer));
  }

  @Override
  public boolean stateful() {
    return false;
  }

  @Override
  public boolean optimizeDataFile() {
    return false;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method blocks waiting for container execution to complete.
   */
  @Override
  public CodeExecutionResult executeCode(
      InvocationContext invocationContext, CodeExecutionInput codeExecutionInput) {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    ExecCreateCmdResponse execCreateCmdResponse =
        dockerClient
            .execCreateCmd(container.getId())
            .withAttachStdout(true)
            .withAttachStderr(true)
            .withCmd("python3", "-c", codeExecutionInput.code())
            .exec();
    try {
      dockerClient
          .execStartCmd(execCreateCmdResponse.getId())
          .exec(new ExecStartResultCallback(stdout, stderr))
          .awaitCompletion();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Code execution was interrupted.", e);
    }

    return CodeExecutionResult.builder()
        .stdout(stdout.toString(StandardCharsets.UTF_8))
        .stderr(stderr.toString(StandardCharsets.UTF_8))
        .build();
  }

  private void buildDockerImage() {
    if (dockerPath.isEmpty()) {
      throw new IllegalStateException("Docker path is not set.");
    }
    File dockerfile = new File(dockerPath.get());
    if (!dockerfile.exists()) {
      throw new UncheckedIOException(new IOException("Invalid Docker path: " + dockerPath.get()));
    }

    logger.info("Building Docker image...");
    try {
      dockerClient.buildImageCmd(dockerfile).withTag(image).start().awaitCompletion();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Docker image build was interrupted.", e);
    }
    logger.info("Docker image: {} built.", image);
  }

  private void verifyPythonInstallation() {
    ExecCreateCmdResponse execCreateCmdResponse =
        dockerClient.execCreateCmd(container.getId()).withCmd("which", "python3").exec();
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    try (ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr)) {
      dockerClient.execStartCmd(execCreateCmdResponse.getId()).exec(callback).awaitCompletion();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Python verification was interrupted.", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void initContainer() {
    if (dockerClient == null) {
      throw new IllegalStateException("Docker client is not initialized.");
    }
    if (dockerPath.isPresent()) {
      buildDockerImage();
    } else {
      // If a dockerPath is not provided, always pull the image to ensure it's up-to-date.
      // If the image already exists locally, this will be a quick no-op.
      logger.info("Ensuring image {} is available locally...", image);
      try {
        dockerClient.pullImageCmd(image).start().awaitCompletion();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Docker image pull was interrupted.", e);
      }
      logger.info("Image {} is available.", image);
    }
    logger.info("Starting container for ContainerCodeExecutor...");
    var createContainerResponse =
        dockerClient.createContainerCmd(image).withTty(true).withAttachStdin(true).exec();
    dockerClient.startContainerCmd(createContainerResponse.getId()).exec();

    var containers = dockerClient.listContainersCmd().withShowAll(true).exec();
    this.container =
        containers.stream()
            .filter(c -> c.getId().equals(createContainerResponse.getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Failed to find the created container."));

    logger.info("Container {} started.", container.getId());
    verifyPythonInstallation();
  }

  private void cleanupContainer() {
    if (container == null) {
      return;
    }
    logger.info("[Cleanup] Stopping the container...");
    dockerClient.stopContainerCmd(container.getId()).exec();
    dockerClient.removeContainerCmd(container.getId()).exec();
    logger.info("Container {} stopped and removed.", container.getId());
    try {
      dockerClient.close();
    } catch (IOException e) {
      logger.warn("Failed to close docker client", e);
    }
  }
}
