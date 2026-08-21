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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ContainerCodeExecutor}'s sandboxing. */
@RunWith(JUnit4.class)
public final class ContainerCodeExecutorTest {

  private static final String IMAGE = "adk-code-executor:latest";
  private static final String CONTAINER_ID = "container-123";
  private static final String EXEC_ID = "exec-456";

  @Test
  public void sandboxHostConfig_appliesFullHardening() {
    ContainerCodeExecutor executor = new ContainerCodeExecutor(mock(DockerClient.class), IMAGE);

    HostConfig hostConfig = executor.sandboxHostConfig();

    assertThat(hostConfig.getNetworkMode()).isEqualTo("none");
    assertThat(hostConfig.getCapDrop()).asList().containsExactly(Capability.ALL);
    assertThat(hostConfig.getReadonlyRootfs()).isTrue();
    assertThat(hostConfig.getSecurityOpts()).containsExactly("no-new-privileges");
    assertThat(hostConfig.getMemory()).isEqualTo(512L * 1024 * 1024);
    assertThat(hostConfig.getPidsLimit()).isEqualTo(128L);
    assertThat(hostConfig.getTmpFs()).containsEntry("/tmp", "rw,size=64m");
  }

  @Test
  public void sandboxHostConfig_networkEnabled_doesNotForceNoneNetwork() {
    ContainerCodeExecutor executor =
        new ContainerCodeExecutor(mock(DockerClient.class), IMAGE).setNetworkEnabled(true);

    HostConfig hostConfig = executor.sandboxHostConfig();

    // When networking is explicitly enabled we leave the network mode at Docker's default.
    assertThat(hostConfig.getNetworkMode()).isNull();
    // The other hardening still applies.
    assertThat(hostConfig.getCapDrop()).asList().containsExactly(Capability.ALL);
    assertThat(hostConfig.getReadonlyRootfs()).isTrue();
  }

  @Test
  public void sandboxHostConfig_customMemoryLimit_applied() {
    ContainerCodeExecutor executor =
        new ContainerCodeExecutor(mock(DockerClient.class), IMAGE)
            .setMemoryLimitBytes(256L * 1024 * 1024);

    assertThat(executor.sandboxHostConfig().getMemory()).isEqualTo(256L * 1024 * 1024);
  }

  @Test
  public void executeCode_strictSandbox_execsInHardenedContainerAndForceRemovesIt() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ true);
    ContainerCodeExecutor executor =
        new ContainerCodeExecutor(client, IMAGE).setStrictSandbox(true);

    CodeExecutionResult result =
        executor.executeCode(
            /* invocationContext= */ null,
            CodeExecutionInput.builder().code("print('hi')").build());

    CreateContainerCmd createCmd = client.createContainerCmd(IMAGE);

    // The container is created with the hardened HostConfig...
    ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
    verify(createCmd).withHostConfig(hostConfigCaptor.capture());
    assertThat(hostConfigCaptor.getValue().getNetworkMode()).isEqualTo("none");
    assertThat(hostConfigCaptor.getValue().getReadonlyRootfs()).isTrue();

    // ...the code runs via docker exec (bypasses ENTRYPOINT; needs only python3)...
    ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
    verify(client.execCreateCmd(CONTAINER_ID)).withCmd(cmdCaptor.capture());
    assertThat(cmdCaptor.getValue())
        .asList()
        .containsExactly("python3", "-c", "print('hi')")
        .inOrder();

    // ...and the container is force-removed afterwards.
    verify(client.startContainerCmd(CONTAINER_ID)).exec();
    verify(client.removeContainerCmd(CONTAINER_ID)).withForce(true);
    verify(client.removeContainerCmd(CONTAINER_ID)).exec();
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  public void executeCode_timeout_returnsTimeoutResultAndForceRemovesContainer() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ false);
    ContainerCodeExecutor executor =
        new ContainerCodeExecutor(client, IMAGE)
            .setStrictSandbox(true)
            .setExecutionTimeoutSeconds(1);

    CodeExecutionResult result =
        executor.executeCode(
            /* invocationContext= */ null,
            CodeExecutionInput.builder().code("while True: pass").build());

    assertThat(result.stderr()).contains("timed out");
    // The runaway container is force-removed, which kills the exec.
    verify(client.removeContainerCmd(CONTAINER_ID)).withForce(true);
    verify(client.removeContainerCmd(CONTAINER_ID)).exec();
  }

  @Test
  public void executeCode_timeout_keepsOutputPrintedBeforeTheKill() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ false);
    // The code prints something, then hangs until the timeout kills it.
    when(client.execStartCmd(EXEC_ID).exec(any()))
        .thenAnswer(
            invocation -> {
              ExecStartResultCallback callback = invocation.getArgument(0);
              callback.onNext(
                  new Frame(StreamType.STDOUT, "step 1 done\n".getBytes(StandardCharsets.UTF_8)));
              return callback;
            });
    ContainerCodeExecutor executor =
        new ContainerCodeExecutor(client, IMAGE)
            .setStrictSandbox(true)
            .setExecutionTimeoutSeconds(1);

    CodeExecutionResult result =
        executor.executeCode(
            /* invocationContext= */ null,
            CodeExecutionInput.builder().code("print('step 1 done'); while True: pass").build());

    // Partial output tells the model how far the execution got before it was killed.
    assertThat(result.stdout()).contains("step 1 done");
    assertThat(result.stderr()).contains("timed out");
  }

  @Test
  public void executeCode_default_doesNotApplyHostConfig() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ true);
    ContainerCodeExecutor executor = new ContainerCodeExecutor(client, IMAGE);

    CodeExecutionResult result =
        executor.executeCode(
            /* invocationContext= */ null,
            CodeExecutionInput.builder().code("print('hi')").build());

    // No hardened HostConfig is applied by default, preserving existing behavior...
    CreateContainerCmd createCmd = client.createContainerCmd(IMAGE);
    verify(createCmd, never()).withHostConfig(any());
    // ...but the code still runs via docker exec.
    verify(client.execCreateCmd(CONTAINER_ID)).withCmd(any(String[].class));
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  public void executeCode_default_reusesContainerAndKeepsItRunning() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ true);
    ContainerCodeExecutor executor = new ContainerCodeExecutor(client, IMAGE);
    CodeExecutionInput input = CodeExecutionInput.builder().code("print('hi')").build();

    executor.executeCode(/* invocationContext= */ null, input);
    executor.executeCode(/* invocationContext= */ null, input);

    // One container is created and started for both executions, as before (and as in ADK Python),
    // so existing callers keep warm-exec latency and a single-container footprint.
    verify(client.startContainerCmd(CONTAINER_ID), times(1)).exec();
    // It is left running between executions rather than removed each time.
    verify(client.removeContainerCmd(CONTAINER_ID), never()).exec();
  }

  @Test
  public void executeCode_strictSandbox_usesFreshContainerPerExecution() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ true);
    ContainerCodeExecutor executor =
        new ContainerCodeExecutor(client, IMAGE).setStrictSandbox(true);
    CodeExecutionInput input = CodeExecutionInput.builder().code("print('hi')").build();

    executor.executeCode(/* invocationContext= */ null, input);
    executor.executeCode(/* invocationContext= */ null, input);

    // Each execution gets its own container, force-removed afterwards, so nothing (including
    // anything written under /tmp) leaks from one execution to the next.
    verify(client.startContainerCmd(CONTAINER_ID), times(2)).exec();
    verify(client.removeContainerCmd(CONTAINER_ID), times(2)).exec();
  }

  @Test
  public void close_removesSharedContainer() throws Exception {
    DockerClient client = mockDockerClient(/* driveCompletion= */ true);
    ContainerCodeExecutor executor = new ContainerCodeExecutor(client, IMAGE);
    executor.executeCode(
        /* invocationContext= */ null, CodeExecutionInput.builder().code("print('hi')").build());

    executor.close();

    verify(client.removeContainerCmd(CONTAINER_ID)).withForce(true);
    verify(client.removeContainerCmd(CONTAINER_ID)).exec();
  }

  @Test
  public void warnIfStrictSandboxDisabled_sandboxDisabled_warnsOnlyOnce() {
    ContainerCodeExecutor executor = new ContainerCodeExecutor(mock(DockerClient.class), IMAGE);

    // The dangerous default is flagged, but only once per executor so it cannot spam the logs.
    assertThat(executor.warnIfStrictSandboxDisabled()).isTrue();
    assertThat(executor.warnIfStrictSandboxDisabled()).isFalse();
  }

  @Test
  public void warnIfStrictSandboxDisabled_strictSandbox_doesNotWarn() {
    ContainerCodeExecutor executor =
        new ContainerCodeExecutor(mock(DockerClient.class), IMAGE).setStrictSandbox(true);

    assertThat(executor.warnIfStrictSandboxDisabled()).isFalse();
  }

  @Test
  public void close_closesDockerClient() throws Exception {
    DockerClient client = mock(DockerClient.class);
    ContainerCodeExecutor executor = new ContainerCodeExecutor(client, IMAGE);

    executor.close();

    verify(client).close();
  }

  /**
   * Builds a mock {@link DockerClient} whose create/start/exec/remove chain succeeds. When {@code
   * driveCompletion} is true the exec callback is completed immediately so {@code awaitCompletion}
   * returns without blocking; otherwise it is left pending so the executor's timeout fires.
   */
  private static DockerClient mockDockerClient(boolean driveCompletion) {
    DockerClient client = mock(DockerClient.class);

    CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
    when(client.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.withHostConfig(any())).thenReturn(createCmd);
    when(createCmd.withTty(any())).thenReturn(createCmd);
    when(createCmd.withAttachStdin(any())).thenReturn(createCmd);
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class);
    when(client.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    ExecCreateCmd execCreateCmd = mock(ExecCreateCmd.class);
    when(client.execCreateCmd(CONTAINER_ID)).thenReturn(execCreateCmd);
    when(execCreateCmd.withAttachStdout(any())).thenReturn(execCreateCmd);
    when(execCreateCmd.withAttachStderr(any())).thenReturn(execCreateCmd);
    when(execCreateCmd.withCmd(any(String[].class))).thenReturn(execCreateCmd);
    ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
    when(execCreateResponse.getId()).thenReturn(EXEC_ID);
    when(execCreateCmd.exec()).thenReturn(execCreateResponse);

    ExecStartCmd execStartCmd = mock(ExecStartCmd.class);
    when(client.execStartCmd(EXEC_ID)).thenReturn(execStartCmd);
    when(execStartCmd.exec(any()))
        .thenAnswer(
            invocation -> {
              ExecStartResultCallback callback = invocation.getArgument(0);
              if (driveCompletion) {
                callback.onComplete();
              }
              return callback;
            });

    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
    when(client.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);
    when(removeCmd.withForce(any())).thenReturn(removeCmd);

    return client;
  }
}
