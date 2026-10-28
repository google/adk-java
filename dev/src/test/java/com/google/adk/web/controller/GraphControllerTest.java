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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.AgentStaticLoader;
import com.google.adk.web.dto.GraphResponse;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Tests the deprecated {@link AgentLoader} constructor, which Spring does not use. */
@SuppressWarnings("deprecation")
class GraphControllerTest {

  private static final String USER_ID = "user";

  private final InMemorySessionService sessionService = new InMemorySessionService();

  @Test
  void getEventGraph_agentLoaderConstructorKnownAgent_findsTheAgent() {
    GraphController controller =
        new GraphController(
            sessionService,
            new AgentStaticLoader(
                LlmAgent.builder().name("assistant").model("gemini-2.5-flash").build()));
    String sessionId = sessionService.createSession("assistant", USER_ID).blockingGet().id();

    ResponseEntity<GraphResponse> response =
        controller.getEventGraph("assistant", USER_ID, sessionId, "unknown_event");

    // An unknown event gets an empty graph only once the app's root agent is found.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void getEventGraph_agentLoaderConstructorUnknownApp_returnsNotFound() {
    GraphController controller = new GraphController(sessionService, new AgentStaticLoader());

    ResponseEntity<GraphResponse> response =
        controller.getEventGraph("missing", USER_ID, "session", "event");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void getEventGraph_agentLoaderConstructorAgentFailsToLoad_returnsServerError() {
    AgentLoader failingLoader =
        new AgentLoader() {
          @Override
          public ImmutableList<String> listAgents() {
            return ImmutableList.of("broken");
          }

          @Override
          public BaseAgent loadAgent(String name) {
            throw new IllegalStateException("cannot build the agent");
          }
        };
    GraphController controller = new GraphController(sessionService, failingLoader);

    ResponseEntity<GraphResponse> response =
        controller.getEventGraph("broken", USER_ID, "session", "event");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
