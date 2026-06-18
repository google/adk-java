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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AgentEngineDeployerTest {

  @Test
  public void build_withMissingProjectId_throwsException() {
    AgentEngineDeployer.Builder builder = AgentEngineDeployer.builder();
    // ProjectId is not set.
    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void deploy_createsDockerfileWithCorrectPort() throws IOException {
    int serverPort = 9090;

    Path tempDir = AgentEngineDeployer.prepareBundle(serverPort);
    Path dockerfile = tempDir.resolve("Dockerfile");

    assertThat(Files.exists(dockerfile)).isTrue();
    String content = Files.readString(dockerfile);
    assertThat(content).contains("EXPOSE " + serverPort);
  }
}
