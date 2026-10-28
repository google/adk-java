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

package com.google.adk.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.adk.apps.App;
import com.google.adk.web.AppLoader;
import com.google.adk.web.InMemoryAppLoader;
import com.google.adk.web.testing.GraphFixtures;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AppGraphControllerTest {

  /** Serves a workflow app and an agent app, and lists one more app that fails to load. */
  @TestConfiguration
  static class Apps {
    @Bean
    AppLoader appLoader() {
      InMemoryAppLoader apps =
          new InMemoryAppLoader(
              App.builder().name("support_app").rootNode(GraphFixtures.supportWorkflow()).build(),
              App.builder().name("assistant").rootAgent(GraphFixtures.assistantAgent()).build());
      return new AppLoader() {
        @Override
        public ImmutableList<String> listApps() {
          return ImmutableList.<String>builder().addAll(apps.listApps()).add("broken").build();
        }

        @Override
        public Optional<App> loadApp(String appName) {
          if (appName.equals("broken")) {
            throw new IllegalStateException("cannot build the app");
          }
          return apps.loadApp(appName);
        }
      };
    }
  }

  @Autowired private MockMvc mockMvc;

  @Test
  void buildGraph_workflowApp_describesItsGraphInSnakeCase() throws Exception {
    mockMvc
        .perform(get("/dev/apps/support_app/build_graph"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("support_app"))
        .andExpect(jsonPath("$.root_agent.type").value("workflow"))
        .andExpect(jsonPath("$.root_agent.graph.nodes[*].name", Matchers.hasItem("tech_flow")))
        .andExpect(jsonPath("$.root_agent.graph.nodes[2].sub_agents").isArray())
        .andExpect(jsonPath("$.root_agent.graph.edges[1].from_node.name").value("classify"))
        .andExpect(jsonPath("$.root_agent.graph.edges[1].route").value("billing"))
        .andExpect(jsonPath("$.root_agent.graph.edges[0].route").doesNotExist());
  }

  @Test
  void buildGraph_agentApp_describesItsTree() throws Exception {
    mockMvc
        .perform(get("/dev/apps/assistant/build_graph"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.root_agent.type").value("agent"))
        .andExpect(jsonPath("$.root_agent.sub_agents[0].name").value("helper"));
  }

  @Test
  void buildGraph_unknownApp_returnsNotFound() throws Exception {
    mockMvc.perform(get("/dev/apps/missing/build_graph")).andExpect(status().isNotFound());
  }

  @Test
  void buildGraph_appFailsToLoad_returnsServerError() throws Exception {
    mockMvc
        .perform(get("/dev/apps/broken/build_graph"))
        .andExpect(status().isInternalServerError());
  }
}
