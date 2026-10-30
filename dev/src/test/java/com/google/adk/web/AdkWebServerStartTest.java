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

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.web.service.RunnerService;
import com.google.adk.web.testing.StepNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** Tests {@link AdkWebServer#start(App, App...)} through the context it starts. */
class AdkWebServerStartTest {

  private static final ImmutableList<String> SERVER_PROPERTIES =
      ImmutableList.of("adk.agents.loader", "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE");

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

  private final Map<String, String> savedProperties = new HashMap<>();

  @BeforeEach
  void saveServerProperties() {
    for (String key : SERVER_PROPERTIES) {
      savedProperties.put(key, System.getProperty(key));
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
}
