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
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A code executor that runs code in a Docker container.
 *
 * <p>Code is run via {@code docker exec} (as in ADK Python), so the image only needs {@code
 * python3} on its PATH; any image {@code ENTRYPOINT} is bypassed. By default a single container is
 * created on first use and reused for every {@link #executeCode} call, as in ADK Python. With the
 * strict sandbox enabled, each execution instead runs in a fresh container that is force-removed
 * afterwards, so one execution cannot observe or affect another's environment.
 *
 * <p><b>Sandboxing is opt-in.</b> By default the execution container is unrestricted (network
 * enabled, writable filesystem, no resource or time limits), matching the previous behavior so
 * existing callers are not broken; a warning is logged when it is used this way. Call {@link
 * #setStrictSandbox(boolean) setStrictSandbox(true)} to harden each container: no network (unless
 * re-enabled via {@link #setNetworkEnabled(boolean)}), all Linux capabilities dropped, no privilege
 * escalation, a read-only root filesystem with a small writable {@code /tmp} tmpfs, memory/PID
 * limits, and a wall-clock execution timeout. Strict sandboxing becomes the default in ADK 2.0.
 *
 * <p>The execution timeout and memory limit used by the strict sandbox are configurable via {@link
 * #setExecutionTimeoutSeconds(long)} and {@link #setMemoryLimitBytes(long)}.
 *
 * <p>This executor holds a {@link DockerClient}; call {@link #close()} (or rely on the registered
 * JVM shutdown hook) to release its connections and threads. As with ADK Python, an abrupt JVM
 * termination (e.g. SIGKILL) during an execution may leave a container behind.
 */
public class ContainerCodeExecutor extends BaseCodeExecutor implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(ContainerCodeExecutor.class);
  private static final String DEFAULT_IMAGE_TAG = "adk-code-executor:latest";

  /** Default memory limit for each execution container (512 MiB). */
  private static final long DEFAULT_MEMORY_LIMIT_BYTES = 512L * 1024 * 1024;

  /** Maximum number of processes/threads allowed inside an execution container. */
  private static final long PIDS_LIMIT = 128L;

  /** Default max wall-clock time a single execution may run before its container is killed. */
  private static final long DEFAULT_EXECUTION_TIMEOUT_SECONDS = 60L;

  private final String baseUrl;
  private final String image;
  private final String dockerPath;
  private final DockerClient dockerClient;
  // Registered by the image/dockerPath constructor as a backstop; removed in close() so a closed
  // executor is not retained by the JVM's shutdown-hook list.
  private final Thread shutdownHook = new Thread(this::close);
  private boolean networkEnabled = false;
  private long executionTimeoutSeconds = DEFAULT_EXECUTION_TIMEOUT_SECONDS;
  private long memoryLimitBytes = DEFAULT_MEMORY_LIMIT_BYTES;

  // Off by default so this executor does not change behavior for existing callers; a warning is
  // logged while it is disabled, and it becomes the default in ADK 2.0.
  private boolean strictSandbox = false;
  private final AtomicBoolean strictSandboxWarningLogged = new AtomicBoolean(false);

  // Container reused across executions while the strict sandbox is off, preserving the previous
  // behavior (and matching ADK Python). Created on first use and removed by close(); the strict
  // sandbox uses a fresh, hardened container per execution instead.
  private String sharedContainerId;

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
    try {
      prepareImage();
    } catch (RuntimeException | Error e) {
      // The caller never receives this instance, so it can never call close(); release the client's
      // connections and threads here instead of leaking them.
      closeDockerClientQuietly();
      throw e;
    }
    // Backstop so the client is released even if callers forget to close() this executor.
    Runtime.getRuntime().addShutdownHook(shutdownHook);
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
   * Enables or disables container networking when the strict sandbox is on. In strict mode
   * networking is disabled by default so executed code cannot reach the network (including the
   * cloud metadata endpoint); pass {@code true} to allow it. Has no effect unless {@link
   * #setStrictSandbox(boolean)} is enabled — without the sandbox the container always has network
   * access.
   */
  public ContainerCodeExecutor setNetworkEnabled(boolean networkEnabled) {
    this.networkEnabled = networkEnabled;
    return this;
  }

  /**
   * Sets the maximum wall-clock time (in seconds) a single execution may run, in the strict
   * sandbox, before its container is force-removed (killed). Defaults to 60 seconds. Has no effect
   * unless {@link #setStrictSandbox(boolean)} is enabled.
   */
  public ContainerCodeExecutor setExecutionTimeoutSeconds(long executionTimeoutSeconds) {
    this.executionTimeoutSeconds = executionTimeoutSeconds;
    return this;
  }

  /**
   * Sets the per-execution container memory limit, in bytes, used by the strict sandbox. Defaults
   * to 512 MiB. Has no effect unless {@link #setStrictSandbox(boolean)} is enabled.
   */
  public ContainerCodeExecutor setMemoryLimitBytes(long memoryLimitBytes) {
    this.memoryLimitBytes = memoryLimitBytes;
    return this;
  }

  /**
   * Enables the strict sandbox. When enabled, each execution runs in its own fresh container
   * (force-removed afterwards) that is hardened: no network (unless re-enabled via {@link
   * #setNetworkEnabled(boolean)}), all Linux capabilities dropped, no privilege escalation, a
   * read-only root filesystem (writable {@code /tmp} only), memory/PID limits, and a wall-clock
   * timeout. While disabled, a single unrestricted container is reused across executions, as
   * before.
   *
   * <p>Disabled by default so enabling the sandbox is not a breaking change for existing callers.
   * While it is disabled a warning is logged, because running untrusted, model-generated code
   * without the sandbox is dangerous. Strict sandboxing becomes the default in ADK 2.0.
   */
  public ContainerCodeExecutor setStrictSandbox(boolean strictSandbox) {
    this.strictSandbox = strictSandbox;
    return this;
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
    warnIfStrictSandboxDisabled();

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    // The strict sandbox gives each execution its own hardened container, force-removed afterwards,
    // so one run cannot observe or affect another's environment. Without it a single unrestricted
    // container is created on first use and reused, preserving the previous behavior (and matching
    // ADK Python). Code is run via `docker exec`, which needs only `python3` on the image and
    // bypasses any ENTRYPOINT.
    boolean perExecutionContainer = strictSandbox;
    String containerId =
        perExecutionContainer ? createAndStartContainer(/* hardened= */ true) : sharedContainer();
    try {
      ExecCreateCmdResponse execCreateCmdResponse =
          dockerClient
              .execCreateCmd(containerId)
              .withAttachStdout(true)
              .withAttachStderr(true)
              .withCmd("python3", "-c", codeExecutionInput.code())
              .exec();

      boolean completed;
      ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr);
      try {
        dockerClient.execStartCmd(execCreateCmdResponse.getId()).exec(callback);
        if (strictSandbox) {
          completed = callback.awaitCompletion(executionTimeoutSeconds, TimeUnit.SECONDS);
        } else {
          // No execution timeout unless the strict sandbox is enabled, matching prior behavior.
          callback.awaitCompletion();
          completed = true;
        }
      } finally {
        closeQuietly(callback);
      }

      if (!completed) {
        // Force-removing the container in the finally block kills the still-running execution
        // (timeouts only apply in the strict sandbox, which always uses a per-execution container).
        // Whatever the code printed before being killed is kept: it is often what tells the model
        // how far the execution got.
        String timedOut =
            String.format("Code execution timed out after %d seconds.", executionTimeoutSeconds);
        String partialStderr = stderr.toString(StandardCharsets.UTF_8);
        return CodeExecutionResult.builder()
            .stdout(stdout.toString(StandardCharsets.UTF_8))
            .stderr(partialStderr.isEmpty() ? timedOut : partialStderr + "\n" + timedOut)
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
      if (perExecutionContainer) {
        removeContainerQuietly(containerId);
      }
    }
  }

  /**
   * Returns the container shared by all executions while the strict sandbox is off, creating and
   * starting it on first use.
   */
  private synchronized String sharedContainer() {
    if (sharedContainerId == null) {
      sharedContainerId = createAndStartContainer(/* hardened= */ false);
    }
    return sharedContainerId;
  }

  /**
   * Creates and starts a container, applying the hardened {@link HostConfig} when {@code hardened}
   * is set. Returns its id.
   */
  private String createAndStartContainer(boolean hardened) {
    var createContainerCmd =
        dockerClient.createContainerCmd(image).withTty(true).withAttachStdin(true);
    if (hardened) {
      createContainerCmd.withHostConfig(sandboxHostConfig());
    }
    CreateContainerResponse createContainerResponse = createContainerCmd.exec();
    String containerId = createContainerResponse.getId();
    dockerClient.startContainerCmd(containerId).exec();
    return containerId;
  }

  /**
   * Closes the exec output stream, logging rather than propagating a failure. The output has
   * already been read by this point, so a teardown error must not fail an otherwise successful
   * execution -- nor add an unchecked exception to {@link #executeCode}'s contract.
   */
  private void closeQuietly(ExecStartResultCallback callback) {
    try {
      callback.close();
    } catch (IOException e) {
      logger.warn("Failed to close the exec output stream", e);
    }
  }

  /** Builds the hardened {@link HostConfig} applied to each execution container in strict mode. */
  @VisibleForTesting
  HostConfig sandboxHostConfig() {
    HostConfig hostConfig =
        HostConfig.newHostConfig()
            .withCapDrop(Capability.ALL)
            .withReadonlyRootfs(true)
            .withSecurityOpts(ImmutableList.of("no-new-privileges"))
            .withMemory(memoryLimitBytes)
            .withPidsLimit(PIDS_LIMIT)
            // A read-only rootfs still needs a small writable scratch space at /tmp.
            .withTmpFs(ImmutableMap.of("/tmp", "rw,size=64m"));
    if (!networkEnabled) {
      hostConfig.withNetworkMode("none");
    }
    return hostConfig;
  }

  /**
   * Logs a warning, at most once per executor, if the strict sandbox is disabled. Returns whether
   * the warning was logged.
   */
  @VisibleForTesting
  boolean warnIfStrictSandboxDisabled() {
    if (!strictSandbox && strictSandboxWarningLogged.compareAndSet(false, true)) {
      logger.warn(
          "ContainerCodeExecutor is running with the strict sandbox disabled (the current default):"
              + " executions share one container, which has network access (including the cloud"
              + " metadata endpoint), a writable filesystem, and no memory, PID or time limits. If"
              + " the code being run is untrusted or model-generated, call setStrictSandbox(true)"
              + " to give each execution its own locked-down container. This becomes the default in"
              + " ADK 2.0.");
      return true;
    }
    return false;
  }

  private void removeContainerQuietly(String containerId) {
    try {
      dockerClient.removeContainerCmd(containerId).withForce(true).exec();
    } catch (RuntimeException e) {
      logger.warn("Failed to remove container {}", containerId, e);
    }
  }

  /**
   * Removes the shared container, if one was created, and closes the underlying Docker client,
   * releasing its connections and threads.
   */
  @Override
  public synchronized void close() {
    if (sharedContainerId != null) {
      removeContainerQuietly(sharedContainerId);
      sharedContainerId = null;
    }
    try {
      // Unregister the shutdown hook so a closed executor is not retained by the JVM. Throws
      // IllegalStateException if the JVM is already shutting down (e.g. close() invoked from the
      // hook itself), in which case there is nothing to remove.
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException e) {
      // JVM shutdown already in progress; the hook cannot (and need not) be removed.
    }
    closeDockerClientQuietly();
  }

  private void closeDockerClientQuietly() {
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
}
