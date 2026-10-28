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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.web.testing.StepNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Tests the server with an {@link AppLoader} bean next to the default compiled agent loader. */
@SpringBootTest
@AutoConfigureMockMvc
class AdkWebServerAppLoaderTest {

  @TestConfiguration
  static class Apps {
    @Bean
    AppLoader appLoader() {
      Workflow workflow =
          Workflow.builder()
              .name("flow")
              .edges(new Edge(Start.INSTANCE, new StepNode("step")))
              .build();
      return new InMemoryAppLoader(
          App.builder().name("flow_app").rootNode(workflow).build(),
          App.builder()
              .name("agent_app")
              .rootAgent(LlmAgent.builder().name("assistant").model("gemini-2.5-flash").build())
              .build());
    }
  }

  @Autowired private MockMvc mockMvc;

  @Test
  void listApps_appLoader_returnsItsAppsSorted() throws Exception {
    mockMvc
        .perform(get("/list-apps"))
        .andExpect(status().isOk())
        .andExpect(content().string("[\"agent_app\",\"flow_app\"]"));
  }

  @Test
  void run_unknownApp_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            post("/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"appName\":\"missing\",\"userId\":\"user\",\"sessionId\":\"session\","
                        + "\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void eventGraph_agentApp_findsTheRootAgent() throws Exception {
    mockMvc
        .perform(
            post("/apps/agent_app/users/user/sessions/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    // An unknown event gets an empty graph only once the app's root agent is found.
    mockMvc
        .perform(get("/apps/agent_app/users/user/sessions/session/events/event/graph"))
        .andExpect(status().isOk());
  }

  @Test
  void eventGraph_workflowApp_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            post("/apps/flow_app/users/user/sessions/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/apps/flow_app/users/user/sessions/session/events/event/graph"))
        .andExpect(status().isNotFound());
  }
}
