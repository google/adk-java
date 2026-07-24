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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.AttachContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ContainerCodeExecutor}'s sandboxing. */
@RunWith(JUnit4.class)
public final class ContainerCodeExecutorTest {

  private static final String IMAGE = "adk-code-executor:latest";
  private static final String CONTAINER_ID = "container-123";

  @Test
  public void sandboxHostConfig_default_appliesFullHardening() {
    ContainerCodeExecutor executor = new ContainerCodeExecutor(mock(DockerClient.class), IMAGE);

    HostConfig hostConfig = executor.sandboxHostConfig();

    assertThat(hostConfig.getNetworkMode()).isEqualTo("none");
    assertThat(hostConfig.getCapDrop()).asList().containsExactly(Capability.ALL);
    assertThat(hostConfig.getReadonlyRootfs()).isTrue();
    assertThat(hostConfig.getSecurityOpts()).containsExactly("no-new-privileges");
    assertThat(hostConfig.getMemory()).isEqualTo(512L * 1024 * 1024);
    assertThat(hostConfig.getPidsLimit()).isEqualTo(128L);
    assertThat(hostConfig.getAutoRemove()).isTrue();
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
    assertThat(hostConfig.getAutoRemove()).isTrue();
  }

  @Test
  public void executeCode_runsHardenedAutoRemoveContainerAsMainProcess_andRemovesIt() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ true);
    ContainerCodeExecutor executor = new ContainerCodeExecutor(client, IMAGE);

    CodeExecutionResult result =
        executor.executeCode(
            /* invocationContext= */ null,
            CodeExecutionInput.builder().code("print('hi')").build());

    CreateContainerCmd createCmd = client.createContainerCmd(IMAGE);

    // The container is created with the hardened, auto-removing HostConfig...
    ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
    verify(createCmd).withHostConfig(hostConfigCaptor.capture());
    assertThat(hostConfigCaptor.getValue().getNetworkMode()).isEqualTo("none");
    assertThat(hostConfigCaptor.getValue().getAutoRemove()).isTrue();
    assertThat(hostConfigCaptor.getValue().getReadonlyRootfs()).isTrue();

    // ...runs the code as its main process, bounded by an in-container `timeout`...
    ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
    verify(createCmd).withCmd(cmdCaptor.capture());
    assertThat(cmdCaptor.getValue())
        .asList()
        .containsExactly("timeout", "-k", "5", "60", "python3", "-c", "print('hi')")
        .inOrder();

    // ...and is force-removed afterwards as a backstop.
    verify(client.startContainerCmd(CONTAINER_ID)).exec();
    verify(client.removeContainerCmd(CONTAINER_ID)).withForce(true);
    verify(client.removeContainerCmd(CONTAINER_ID)).exec();
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  public void executeCode_usesConfiguredExecutionTimeout() {
    DockerClient client = mockDockerClient(/* driveCompletion= */ true);
    ContainerCodeExecutor executor = new ContainerCodeExecutor(client, IMAGE);
    executor.setExecutionTimeoutSeconds(30);

    executor.executeCode(
        /* invocationContext= */ null, CodeExecutionInput.builder().code("x = 1").build());

    ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
    verify(client.createContainerCmd(IMAGE)).withCmd(cmdCaptor.capture());
    assertThat(cmdCaptor.getValue()).asList().containsAtLeast("timeout", "30", "python3").inOrder();
  }

  @Test
  public void close_closesDockerClient() throws Exception {
    DockerClient client = mock(DockerClient.class);
    ContainerCodeExecutor executor = new ContainerCodeExecutor(client, IMAGE);

    executor.close();

    verify(client).close();
  }

  /**
   * Builds a mock {@link DockerClient} whose create/attach/start/remove chain succeeds. When {@code
   * driveCompletion} is true the attach callback is completed immediately so {@code
   * awaitCompletion} returns without blocking.
   */
  private static DockerClient mockDockerClient(boolean driveCompletion) {
    DockerClient client = mock(DockerClient.class);

    CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
    when(client.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.withHostConfig(any())).thenReturn(createCmd);
    when(createCmd.withCmd(any(String[].class))).thenReturn(createCmd);
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(createCmd.exec()).thenReturn(createResponse);

    AttachContainerCmd attachCmd = mock(AttachContainerCmd.class);
    when(client.attachContainerCmd(CONTAINER_ID)).thenReturn(attachCmd);
    when(attachCmd.withStdOut(any())).thenReturn(attachCmd);
    when(attachCmd.withStdErr(any())).thenReturn(attachCmd);
    when(attachCmd.withFollowStream(any())).thenReturn(attachCmd);
    when(attachCmd.exec(any()))
        .thenAnswer(
            invocation -> {
              AttachContainerResultCallback callback = invocation.getArgument(0);
              if (driveCompletion) {
                callback.onComplete();
              }
              return callback;
            });

    StartContainerCmd startCmd = mock(StartContainerCmd.class);
    when(client.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
    when(client.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);
    when(removeCmd.withForce(any())).thenReturn(removeCmd);

    return client;
  }
}
