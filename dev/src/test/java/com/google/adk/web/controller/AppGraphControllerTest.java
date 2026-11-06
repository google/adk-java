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

import static com.google.adk.web.testing.GraphFixtures.step;
import static com.google.common.truth.Truth.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.apps.App;
import com.google.adk.web.AppLoader;
import com.google.adk.web.InMemoryAppLoader;
import com.google.adk.web.testing.GraphFixtures;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
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

  private static final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Serves a workflow app, an agent app and a two-level nested workflow app, and lists one more app
   * that fails to load.
   */
  @TestConfiguration
  static class Apps {
    @Bean
    AppLoader appLoader() {
      Workflow inner =
          Workflow.builder().name("inner").edges(new Edge(Start.INSTANCE, step("leaf"))).build();
      Workflow middle =
          Workflow.builder().name("middle").edges(new Edge(Start.INSTANCE, inner)).build();
      Workflow outer =
          Workflow.builder().name("outer").edges(new Edge(Start.INSTANCE, middle)).build();
      InMemoryAppLoader apps =
          new InMemoryAppLoader(
              App.builder().name("support_app").rootNode(GraphFixtures.supportWorkflow()).build(),
              App.builder().name("assistant").rootAgent(GraphFixtures.assistantAgent()).build(),
              App.builder().name("nested").rootNode(outer).build());
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

  @Test
  void buildGraphImage_workflowApp_drawsTheRootAndEveryNestedWorkflow() throws Exception {
    JsonNode graphs = json("/dev/apps/support_app/build_graph_image?dark_mode=false");

    assertThat(ImmutableList.copyOf(graphs.fieldNames()))
        .containsExactly("", "support/tech_flow")
        .inOrder();
    assertThat(dotSrc(graphs, "")).contains("__START__ -> classify");
    assertThat(dotSrc(graphs, "")).contains("classify -> tech_flow [label=\"  tech\"]");
    assertThat(dotSrc(graphs, "")).contains("bgcolor=\"#F8FAFC\"");
    assertThat(dotSrc(graphs, "support/tech_flow")).contains("diagnose -> fix");
  }

  @Test
  void buildGraphImage_nestedWorkflows_prefixesNonRootKeysWithTheRootName() throws Exception {
    JsonNode graphs = json("/dev/apps/nested/build_graph_image");

    assertThat(ImmutableList.copyOf(graphs.fieldNames()))
        .containsExactly("", "outer/middle", "outer/middle/inner")
        .inOrder();
    assertThat(dotSrc(graphs, "outer/middle/inner")).contains("__START__ -> leaf");
  }

  @Test
  void buildGraphImage_darkModeValues_selectDarkOrLightBackground() throws Exception {
    assertThat(dotSrc(json("/dev/apps/support_app/build_graph_image?dark_mode=true"), ""))
        .contains("bgcolor=\"#0F172A\"");
    assertThat(dotSrc(json("/dev/apps/support_app/build_graph_image?dark_mode=YES"), ""))
        .contains("bgcolor=\"#0F172A\"");
    assertThat(dotSrc(json("/dev/apps/support_app/build_graph_image"), ""))
        .contains("bgcolor=\"#F8FAFC\"");
    assertThat(dotSrc(json("/dev/apps/support_app/build_graph_image?dark_mode=0"), ""))
        .contains("bgcolor=\"#F8FAFC\"");
  }

  @Test
  void buildGraphImage_darkMode_appliesToNestedAndDrillInGraphs() throws Exception {
    JsonNode graphs = json("/dev/apps/support_app/build_graph_image?dark_mode=true");
    JsonNode nested = json("/dev/apps/support_app/build_graph_image?node=tech_flow&dark_mode=true");

    assertThat(dotSrc(graphs, "support/tech_flow")).contains("bgcolor=\"#0F172A\"");
    assertThat(nested.get("dotSrc").asText()).contains("bgcolor=\"#0F172A\"");
  }

  @Test
  void buildGraphImage_node_returnsBareDotGraphForTarget() throws Exception {
    JsonNode nested = json("/dev/apps/support_app/build_graph_image?node=tech_flow");
    JsonNode agent = json("/dev/apps/support_app/build_graph_image?node=support/billing_agent");

    assertThat(ImmutableList.copyOf(nested.fieldNames())).containsExactly("dotSrc");
    assertThat(nested.get("dotSrc").asText()).contains("diagnose -> fix");
    assertThat(ImmutableList.copyOf(agent.fieldNames())).containsExactly("dotSrc");
    assertThat(agent.get("dotSrc").asText()).contains("billing_agent -> lookup_invoice");
    assertThat(agent.get("dotSrc").asText()).doesNotContain("__START__");
  }

  @Test
  void buildGraphImage_emptyNode_returnsThePathKeyedMap() throws Exception {
    JsonNode graphs = json("/dev/apps/support_app/build_graph_image?node=");

    assertThat(ImmutableList.copyOf(graphs.fieldNames()))
        .containsExactly("", "support/tech_flow")
        .inOrder();
  }

  @Test
  void buildGraphImage_nodeNamingTheRoot_returnsTheRootGraph() throws Exception {
    JsonNode root = json("/dev/apps/support_app/build_graph_image?node=support");

    assertThat(ImmutableList.copyOf(root.fieldNames())).containsExactly("dotSrc");
    assertThat(root.get("dotSrc").asText()).contains("__START__ -> classify");
  }

  @Test
  void buildGraphImage_agentApp_drawsItsTree() throws Exception {
    JsonNode graphs = json("/dev/apps/assistant/build_graph_image");

    assertThat(ImmutableList.copyOf(graphs.fieldNames())).containsExactly("");
    assertThat(dotSrc(graphs, "")).contains("assistant -> helper");
    assertThat(dotSrc(graphs, "")).doesNotContain("__END__");
  }

  @Test
  void buildGraphImage_unknownNode_returnsNotFound() throws Exception {
    mockMvc
        .perform(get("/dev/apps/support_app/build_graph_image?node=tech_flow/missing"))
        .andExpect(status().isNotFound());
  }

  @Test
  void buildGraphImage_unknownApp_returnsNotFound() throws Exception {
    mockMvc.perform(get("/dev/apps/missing/build_graph_image")).andExpect(status().isNotFound());
  }

  @Test
  void buildGraphImage_invalidDarkMode_returnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/dev/apps/support_app/build_graph_image?dark_mode=maybe"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void buildGraphImage_emptyDarkMode_returnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/dev/apps/support_app/build_graph_image?dark_mode="))
        .andExpect(status().isBadRequest());
  }

  private JsonNode json(String path) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(get(path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static String dotSrc(JsonNode graphs, String path) {
    return graphs.get(path).get("dotSrc").asText();
  }
}
