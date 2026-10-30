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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.web.testing.StepNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/** Runs apps end to end through {@code /run} and {@code /run_sse}. */
@SpringBootTest
@AutoConfigureMockMvc
class AdkWebServerAppRunTest {

  private static final String USER_ID = "user";

  /** The names of the plugins whose before-run callback ran, in order. */
  private static final List<String> beforeRunCalls = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class Apps {
    @Bean
    AppLoader appLoader() {
      App flowApp =
          App.builder()
              .name("flow_app")
              .rootNode(greetingWorkflow())
              .plugins(new RecordingPlugin("app_plugin"))
              .build();
      App resumableApp =
          App.builder()
              .name("resumable_app")
              .rootNode(greetingWorkflow())
              .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
              .build();
      App greeter = App.builder().name("greeter").rootNode(greetingWorkflow()).build();
      InMemoryAppLoader apps = new InMemoryAppLoader(flowApp, resumableApp);
      // Serves greeter under "alias", not its own name, as a loader keyed by something else may.
      return new AppLoader() {
        @Override
        public ImmutableList<String> listApps() {
          return ImmutableList.<String>builder().addAll(apps.listApps()).add("alias").build();
        }

        @Override
        public Optional<App> loadApp(String appName) {
          return appName.equals("alias") ? Optional.of(greeter) : apps.loadApp(appName);
        }
      };
    }

    @Bean(name = "extraPlugins")
    List<BasePlugin> extraPlugins() {
      return ImmutableList.of(new RecordingPlugin("server_plugin"));
    }
  }

  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void clearCalls() {
    beforeRunCalls.clear();
  }

  @Test
  void run_workflowApp_runsTheGraph() throws Exception {
    String sessionId = createSession("flow_app");

    mockMvc
        .perform(runRequest("/run", "flow_app", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].output", Matchers.hasItems("greet", "done")))
        .andExpect(jsonPath("$[*].nodeInfo.path", Matchers.hasItem("flow@1/done@1")))
        .andExpect(jsonPath("$[*].actions.agentState").isEmpty());
  }

  @Test
  void run_appAndServerPlugins_runsTheAppsFirst() throws Exception {
    String sessionId = createSession("flow_app");

    mockMvc.perform(runRequest("/run", "flow_app", sessionId)).andExpect(status().isOk());

    assertThat(beforeRunCalls).containsExactly("app_plugin", "server_plugin").inOrder();
  }

  @Test
  void runSse_workflowApp_streamsGraphEvents() throws Exception {
    String sessionId = createSession("flow_app");

    String stream = runSse("flow_app", sessionId);

    assertThat(stream).contains("\"output\":\"greet\"");
    assertThat(stream).contains("\"output\":\"done\"");
  }

  @Test
  void run_resumableApp_emitsTheWorkflowCheckpoints() throws Exception {
    String sessionId = createSession("resumable_app");

    mockMvc
        .perform(runRequest("/run", "resumable_app", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].actions.agentState.nodes").isNotEmpty())
        .andExpect(jsonPath("$[*].output", Matchers.hasItem("done")));
  }

  @Test
  void runSse_resumableApp_streamsTheWorkflowCheckpoints() throws Exception {
    String sessionId = createSession("resumable_app");

    String stream = runSse("resumable_app", sessionId);

    assertThat(stream).contains("\"agentState\":{\"nodes\":");
    assertThat(stream).contains("\"output\":\"done\"");
  }

  @Test
  void run_appServedUnderAnotherName_keepsTheSessionUnderTheRequestedName() throws Exception {
    String sessionId = createSession("alias");

    mockMvc.perform(runRequest("/run", "alias", sessionId)).andExpect(status().isOk());

    mockMvc
        .perform(get("/apps/alias/users/" + USER_ID + "/sessions/" + sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events[*].output", Matchers.hasItem("done")));
  }

  private String createSession(String appName) throws Exception {
    String sessionId = UUID.randomUUID().toString();
    mockMvc
        .perform(
            post("/apps/" + appName + "/users/" + USER_ID + "/sessions/" + sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    return sessionId;
  }

  private String runSse(String appName, String sessionId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(runRequest("/run_sse", appName, sessionId))
            .andExpect(request().asyncStarted())
            .andReturn();
    // A stream that ended in an error would leave the error as the async result.
    assertThat(result.getAsyncResult(10_000)).isNull();
    return result.getResponse().getContentAsString();
  }

  private static RequestBuilder runRequest(String path, String appName, String sessionId) {
    return post(path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            String.format(
                """
                {"appName": "%s", "userId": "%s", "sessionId": "%s",
                 "newMessage": {"role": "user", "parts": [{"text": "hi"}]}}
                """,
                appName, USER_ID, sessionId));
  }

  private static Workflow greetingWorkflow() {
    Node greet = new StepNode("greet");
    return Workflow.builder()
        .name("flow")
        .edges(new Edge(Start.INSTANCE, greet), new Edge(greet, new StepNode("done")))
        .build();
  }

  /** Records that its before-run callback ran. */
  private static final class RecordingPlugin extends BasePlugin {
    RecordingPlugin(String name) {
      super(name);
    }

    @Override
    public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
      beforeRunCalls.add(getName());
      return Maybe.empty();
    }
  }
}
