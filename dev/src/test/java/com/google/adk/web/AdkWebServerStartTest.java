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

package com.google.adk.web;

import static com.google.common.truth.Truth.assertThat;
import static org.springframework.http.HttpHeaders.HOST;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.web.service.RunnerService;
import com.google.adk.web.testing.StepNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/** Tests the {@link AdkWebServer} entry points through the contexts they start. */
class AdkWebServerStartTest {

  private static final ImmutableList<String> SERVER_PROPERTIES =
      ImmutableList.of(
          "adk.agents.loader", "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE", "server.address");

  private static final App FLOW_APP =
      App.builder()
          .name("flow_app")
          .rootNode(
              Workflow.builder()
                  .name("flow")
                  .edges(new Edge(Start.INSTANCE, new StepNode("step")))
                  .build())
          .build();
  private static final App AGENT_APP =
      App.builder()
          .name("agent_app")
          .rootAgent(LlmAgent.builder().name("assistant").model("gemini-2.5-flash").build())
          .build();

  private final Map<String, @Nullable String> savedProperties = new HashMap<>();

  @BeforeEach
  void saveAndClearServerProperties() {
    for (String key : SERVER_PROPERTIES) {
      savedProperties.put(key, System.getProperty(key));
      System.clearProperty(key);
    }
  }

  @AfterEach
  void restoreServerProperties() {
    // The server sets adk.agents.loader JVM-wide, which would change the loader later tests get.
    savedProperties.forEach(
        (key, value) -> {
          if (value == null) {
            System.clearProperty(key);
          } else {
            System.setProperty(key, value);
          }
        });
  }

  @Test
  void startApps_twoApps_servesThemWithoutTheCompiledAgentLoader() {
    try (ConfigurableApplicationContext context =
        AdkWebServer.startApps(new String[] {"--server.port=0"}, FLOW_APP, AGENT_APP)) {
      assertThat(context.getBean(RunnerService.class).listApps())
          .containsExactly("agent_app", "flow_app");
      assertThat(context.getBeansOfType(AgentLoader.class)).isEmpty();
    }
  }

  @Test
  void run_noAddress_listensOnLoopbackOnly() {
    try (ConfigurableApplicationContext context =
        AdkWebServer.run(new String[] {"--server.port=0"})) {
      assertThat(boundAddress(context)).isEqualTo(InetAddresses.forString("127.0.0.1"));
    }
  }

  @Test
  void startApps_noAddress_listensOnLoopbackOnly() {
    try (ConfigurableApplicationContext context =
        AdkWebServer.startApps(new String[] {"--server.port=0"}, AGENT_APP)) {
      assertThat(boundAddress(context)).isEqualTo(InetAddresses.forString("127.0.0.1"));
    }
  }

  @Test
  void startApps_noAddress_rejectsForeignHost() {
    try (ConfigurableApplicationContext context =
        AdkWebServer.startApps(new String[] {"--server.port=0"}, AGENT_APP)) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();

      HttpStatusCode status =
          RestClient.create()
              .get()
              .uri("http://127.0.0.1:" + port + "/list-apps")
              .header(HOST, "evil.example")
              .exchange((request, response) -> response.getStatusCode());

      assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void startApps_blankAddress_listensOnLoopbackOnly(String address) {
    try (ConfigurableApplicationContext context =
        AdkWebServer.startApps(
            new String[] {"--server.port=0", "--server.address=" + address}, AGENT_APP)) {
      assertThat(boundAddress(context)).isEqualTo(InetAddresses.forString("127.0.0.1"));
    }
  }

  @Test
  void startApps_addressSystemProperty_listensOnIt() {
    System.setProperty("server.address", "0.0.0.0");

    try (ConfigurableApplicationContext context =
        AdkWebServer.startApps(new String[] {"--server.port=0"}, AGENT_APP)) {
      assertThat(boundAddress(context)).isEqualTo(InetAddresses.forString("0.0.0.0"));
    }
  }

  /**
   * Returns the address Tomcat binds its listening socket to, or {@code null} if none is set, which
   * means every interface.
   */
  private static @Nullable InetAddress boundAddress(ConfigurableApplicationContext context) {
    WebServer server = ((WebServerApplicationContext) context).getWebServer();
    Connector connector = ((TomcatWebServer) server).getTomcat().getConnector();
    return ((AbstractProtocol<?>) connector.getProtocolHandler()).getAddress();
  }
}
