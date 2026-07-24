/*
 * Copyright 2026 Google LLC
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

package com.google.adk.codeexecutors;

import static java.util.Objects.requireNonNullElse;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A code executor that runs code in a sandboxed Docker container.
 *
 * <p>A fresh container is created for every {@link #executeCode} call so code from one session
 * cannot observe or affect another session's execution environment. Each container is hardened: no
 * network (so the code cannot reach the cloud metadata endpoint), all Linux capabilities dropped,
 * no privilege escalation, a read-only root filesystem with a small writable {@code /tmp} tmpfs,
 * and memory/PID limits. The code runs as the container's main process wrapped in an in-container
 * {@code timeout}, and the container is set to auto-remove on exit; so even if this JVM dies, the
 * OS kills the code and Docker removes the container rather than leaving a resource-draining
 * "zombie".
 *
 * <p>This executor holds a {@link DockerClient}; call {@link #close()} (or rely on the registered
 * JVM shutdown hook) to release its connections and threads.
 */
public class ContainerCodeExecutor extends BaseCodeExecutor implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(ContainerCodeExecutor.class);
  private static final String DEFAULT_IMAGE_TAG = "adk-code-executor:latest";

  /** Memory limit for each execution container (512 MiB). */
  private static final long MEMORY_LIMIT_BYTES = 512L * 1024 * 1024;

  /** Maximum number of processes/threads allowed inside an execution container. */
  private static final long PIDS_LIMIT = 128L;

  /** Maximum wall-clock time a single execution may run before its container is killed. */
  private static final long EXECUTION_TIMEOUT_SECONDS = 60L;

  /**
   * Extra time to wait for the container to exit after its in-container timeout should have fired.
   */
  private static final long COMPLETION_GRACE_SECONDS = 10L;

  private final String baseUrl;
  private final String image;
  private final String dockerPath;
  private final DockerClient dockerClient;
  private boolean networkEnabled = false;
  private long executionTimeoutSeconds = EXECUTION_TIMEOUT_SECONDS;

  /**
   * Creates a ContainerCodeExecutor from an image.
   *
   * @param baseUrl The base url of the user hosted Docker client.
   * @param image The tag of the predefined image or custom image to run on the container.
   */
  public static ContainerCodeExecutor fromImage(String baseUrl, String image) {
    return new ContainerCodeExecutor(baseUrl, image, null);
  }

  /**
   * Creates a ContainerCodeExecutor from an image.
   *
   * @param image The tag of the predefined image or custom image to run on the container.
   */
  public static ContainerCodeExecutor fromImage(String image) {
    return new ContainerCodeExecutor(null, image, null);
  }

  /**
   * Creates a ContainerCodeExecutor from a Dockerfile path.
   *
   * @param baseUrl The base url of the user hosted Docker client.
   * @param dockerPath The path to the directory containing the Dockerfile.
   */
  public static ContainerCodeExecutor fromDockerPath(String baseUrl, String dockerPath) {
    return new ContainerCodeExecutor(baseUrl, null, dockerPath);
  }

  /**
   * Creates a ContainerCodeExecutor from a Dockerfile path.
   *
   * @param dockerPath The path to the directory containing the Dockerfile.
   */
  public static ContainerCodeExecutor fromDockerPath(String dockerPath) {
    return new ContainerCodeExecutor(null, null, dockerPath);
  }

  /**
   * Initializes the ContainerCodeExecutor. Either dockerPath or image must be set.
   *
   * @deprecated Use one of the static factory methods instead.
   */
  @Deprecated
  public ContainerCodeExecutor(String baseUrl, String image, String dockerPath) {
    if (image == null && dockerPath == null) {
      throw new IllegalArgumentException(
          "Either image or dockerPath must be set for ContainerCodeExecutor.");
    }
    this.baseUrl = baseUrl;
    this.image = requireNonNullElse(image, DEFAULT_IMAGE_TAG);
    this.dockerPath = dockerPath == null ? null : Paths.get(dockerPath).toAbsolutePath().toString();
    this.dockerClient = buildDockerClient(baseUrl);
    prepareImage();
    // Backstop so the client is released even if callers forget to close() this executor.
    Runtime.getRuntime().addShutdownHook(new Thread(this::close));
  }

  /** Test-only constructor that injects a Docker client and skips image preparation. */
  @VisibleForTesting
  ContainerCodeExecutor(DockerClient dockerClient, String image) {
    this.baseUrl = null;
    this.image = requireNonNullElse(image, DEFAULT_IMAGE_TAG);
    this.dockerPath = null;
    this.dockerClient = dockerClient;
  }

  /**
   * Enables or disables container networking. Networking is disabled by default so executed code
   * cannot reach the network, including the cloud metadata endpoint.
   */
  public ContainerCodeExecutor setNetworkEnabled(boolean networkEnabled) {
    this.networkEnabled = networkEnabled;
    return this;
  }

  @VisibleForTesting
  void setExecutionTimeoutSeconds(long executionTimeoutSeconds) {
    this.executionTimeoutSeconds = executionTimeoutSeconds;
  }

  @Override
  public boolean stateful() {
    return false;
  }

  @Override
  public boolean optimizeDataFile() {
    return false;
  }

  @Override
  public CodeExecutionResult executeCode(
      InvocationContext invocationContext, CodeExecutionInput codeExecutionInput) {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    // Run the code as the container's own main process, wrapped in an in-container `timeout` so the
    // OS terminates a runaway even if this JVM dies. A fresh, auto-removing container per execution
    // isolates each run and guarantees cleanup.
    CreateContainerResponse createContainerResponse =
        dockerClient
            .createContainerCmd(image)
            .withHostConfig(sandboxHostConfig())
            .withCmd(
                "timeout",
                "-k",
                "5",
                Long.toString(executionTimeoutSeconds),
                "python3",
                "-c",
                codeExecutionInput.code())
            .exec();
    String containerId = createContainerResponse.getId();
    try {
      // Attach before starting so all output is captured before auto-remove reaps the container.
      AttachContainerResultCallback attachCallback =
          dockerClient
              .attachContainerCmd(containerId)
              .withStdOut(true)
              .withStdErr(true)
              .withFollowStream(true)
              .exec(new FrameCapturingCallback(stdout, stderr));
      dockerClient.startContainerCmd(containerId).exec();

      boolean completed =
          attachCallback.awaitCompletion(
              executionTimeoutSeconds + COMPLETION_GRACE_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        // The in-container timeout should have exited the container already; force-remove it below.
        return CodeExecutionResult.builder()
            .stderr(
                String.format(
                    "Code execution timed out after %d seconds.", executionTimeoutSeconds))
            .build();
      }
      return CodeExecutionResult.builder()
          .stdout(stdout.toString(StandardCharsets.UTF_8))
          .stderr(stderr.toString(StandardCharsets.UTF_8))
          .build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Code execution was interrupted.", e);
    } finally {
      // Backstop: auto-remove usually reaped the container already; force-remove any remnant.
      removeContainerQuietly(containerId);
    }
  }

  /** Builds the hardened {@link HostConfig} applied to every execution container. */
  @VisibleForTesting
  HostConfig sandboxHostConfig() {
    HostConfig hostConfig =
        HostConfig.newHostConfig()
            .withCapDrop(Capability.ALL)
            .withReadonlyRootfs(true)
            .withSecurityOpts(ImmutableList.of("no-new-privileges"))
            .withMemory(MEMORY_LIMIT_BYTES)
            .withPidsLimit(PIDS_LIMIT)
            // Remove the container when it exits so an abruptly-killed JVM cannot leak containers.
            .withAutoRemove(true)
            // A read-only rootfs still needs a small writable scratch space at /tmp.
            .withTmpFs(ImmutableMap.of("/tmp", "rw,size=64m"));
    if (!networkEnabled) {
      hostConfig.withNetworkMode("none");
    }
    return hostConfig;
  }

  private void removeContainerQuietly(String containerId) {
    try {
      dockerClient.removeContainerCmd(containerId).withForce(true).exec();
    } catch (RuntimeException e) {
      // The container was most likely already auto-removed on exit; this is expected.
      logger.debug("Container {} was already removed or could not be removed.", containerId, e);
    }
  }

  /** Closes the underlying Docker client, releasing its connections and threads. */
  @Override
  public void close() {
    try {
      dockerClient.close();
    } catch (IOException e) {
      logger.warn("Failed to close docker client", e);
    }
  }

  private static DockerClient buildDockerClient(String baseUrl) {
    if (baseUrl != null) {
      var config =
          DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(baseUrl).build();
      return DockerClientBuilder.getInstance(config).build();
    }
    return DockerClientBuilder.getInstance().build();
  }

  private void prepareImage() {
    if (dockerPath != null) {
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
  }

  private void buildDockerImage() {
    if (dockerPath == null) {
      throw new IllegalStateException("Docker path is not set.");
    }
    File dockerfile = new File(dockerPath);
    if (!dockerfile.exists()) {
      throw new UncheckedIOException(new IOException("Invalid Docker path: " + dockerPath));
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

  /** Routes container output frames into separate stdout and stderr buffers. */
  private static final class FrameCapturingCallback extends AttachContainerResultCallback {
    private final ByteArrayOutputStream stdout;
    private final ByteArrayOutputStream stderr;

    FrameCapturingCallback(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
      this.stdout = stdout;
      this.stderr = stderr;
    }

    @Override
    public void onNext(Frame frame) {
      try {
        if (frame.getStreamType() == StreamType.STDERR) {
          stderr.write(frame.getPayload());
        } else {
          stdout.write(frame.getPayload());
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      super.onNext(frame);
    }
  }
}
