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

package com.google.adk.tools.mcp;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StdioServerParameters}. */
@RunWith(JUnit4.class)
public final class StdioServerParametersTest {

  @Test
  public void toServerParameters_withNullArgs_createsValidServerParameters() {
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").args(null).build();

    ServerParameters serverParams = params.toServerParameters();

    assertThat(serverParams).isNotNull();
    StdioClientTransport transport = new StdioClientTransport(serverParams);
    assertThat(transport).isNotNull();
  }

  @Test
  public void toServerParameters_withNullEnv_createsValidServerParameters() {
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").env(null).build();

    ServerParameters serverParams = params.toServerParameters();

    assertThat(serverParams).isNotNull();
    StdioClientTransport transport = new StdioClientTransport(serverParams);
    assertThat(transport).isNotNull();
  }

  @Test
  public void toServerParameters_withNonNullArgs_createsValidServerParameters() {
    ImmutableList<String> args = ImmutableList.of("arg1", "arg2");
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").args(args).build();

    ServerParameters serverParams = params.toServerParameters();

    assertThat(serverParams).isNotNull();
    StdioClientTransport transport = new StdioClientTransport(serverParams);
    assertThat(transport).isNotNull();
  }

  @Test
  public void toServerParameters_withNonNullEnv_createsValidServerParameters() {
    ImmutableMap<String, String> env = ImmutableMap.of("KEY1", "value1", "KEY2", "value2");
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").env(env).build();

    ServerParameters serverParams = params.toServerParameters();

    assertThat(serverParams).isNotNull();
    StdioClientTransport transport = new StdioClientTransport(serverParams);
    assertThat(transport).isNotNull();
  }

  @Test
  public void toServerParameters_withAllFieldsSet_createsValidServerParameters() {
    ImmutableList<String> args = ImmutableList.of("arg1", "arg2");
    ImmutableMap<String, String> env = ImmutableMap.of("KEY1", "value1");

    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").args(args).env(env).build();

    ServerParameters serverParams = params.toServerParameters();

    assertThat(serverParams).isNotNull();
    StdioClientTransport transport = new StdioClientTransport(serverParams);
    assertThat(transport).isNotNull();
  }

  @Test
  public void toServerParameters_withOnlyCommand_createsValidServerParameters() {
    StdioServerParameters params = StdioServerParameters.builder().command("test-command").build();

    ServerParameters serverParams = params.toServerParameters();

    assertThat(serverParams).isNotNull();
    StdioClientTransport transport = new StdioClientTransport(serverParams);
    assertThat(transport).isNotNull();
  }

  @Test
  public void builder_withNullArgsAndEnv_buildsSuccessfully() {
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").args(null).env(null).build();

    assertThat(params.command()).isEqualTo("test-command");
    assertThat(params.args()).isNull();
    assertThat(params.env()).isNull();
  }

  @Test
  public void builder_withEmptyArgsAndEnv_buildsSuccessfully() {
    ImmutableList<String> emptyArgs = ImmutableList.of();
    ImmutableMap<String, String> emptyEnv = ImmutableMap.of();

    StdioServerParameters params =
        StdioServerParameters.builder()
            .command("test-command")
            .args(emptyArgs)
            .env(emptyEnv)
            .build();

    assertThat(params.command()).isEqualTo("test-command");
    assertThat(params.args()).isEmpty();
    assertThat(params.env()).isEmpty();
  }

  @Test
  public void toServerParameters_nullArgsNotPassedToBuilder() throws Exception {
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").args(null).env(null).build();

    ServerParameters serverParams = params.toServerParameters();

    Field argsField = serverParams.getClass().getDeclaredField("args");
    argsField.setAccessible(true);
    Object argsValue = argsField.get(serverParams);

    assertThat(argsValue).isNotNull();
  }

  @Test
  public void toServerParameters_nullEnvNotPassedToBuilder() throws Exception {
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").args(null).env(null).build();

    ServerParameters serverParams = params.toServerParameters();

    Field envField = serverParams.getClass().getDeclaredField("env");
    envField.setAccessible(true);
    Object envValue = envField.get(serverParams);

    assertThat(envValue).isNotNull();
  }

  @Test
  public void toServerParameters_nonNullArgsPassedToBuilder() throws Exception {
    ImmutableList<String> args = ImmutableList.of("arg1", "arg2");
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").args(args).build();

    ServerParameters serverParams = params.toServerParameters();

    Field argsField = serverParams.getClass().getDeclaredField("args");
    argsField.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> argsValue = (List<String>) argsField.get(serverParams);

    assertThat(argsValue).containsExactly("arg1", "arg2").inOrder();
  }

  @Test
  public void toServerParameters_nonNullEnvPassedToBuilder() throws Exception {
    ImmutableMap<String, String> env = ImmutableMap.of("KEY1", "value1", "KEY2", "value2");
    StdioServerParameters params =
        StdioServerParameters.builder().command("test-command").env(env).build();

    ServerParameters serverParams = params.toServerParameters();

    Field envField = serverParams.getClass().getDeclaredField("env");
    envField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, String> envValue = (Map<String, String>) envField.get(serverParams);

    assertThat(envValue).containsAtLeast("KEY1", "value1", "KEY2", "value2");
  }
}
