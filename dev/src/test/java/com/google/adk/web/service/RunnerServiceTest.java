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

package com.google.adk.web.service;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.AppLoader;
import com.google.adk.web.CompiledAgentLoader;
import com.google.adk.web.InMemoryAppLoader;
import com.google.adk.web.config.AgentLoadingProperties;
import com.google.adk.web.testing.StepNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RunnerServiceTest {

  private static final Workflow WORKFLOW =
      Workflow.builder().name("flow").edges(new Edge(Start.INSTANCE, new StepNode("step"))).build();
  private static final BaseAgent AGENT =
      LlmAgent.builder().name("assistant").model("gemini-2.5-flash").build();
  private static final BasePlugin APP_PLUGIN = new BasePlugin("app_plugin") {};
  private static final BasePlugin SERVER_PLUGIN = new BasePlugin("server_plugin") {};
  private static final App FLOW_APP =
      App.builder().name("flow_app").rootNode(WORKFLOW).plugins(APP_PLUGIN).build();
  private static final App AGENT_APP = App.builder().name("agent_app").rootAgent(AGENT).build();

  @Test
  void listApps_appLoader_returnsItsApps() {
    RunnerService service = runnerService(new InMemoryAppLoader(FLOW_APP, AGENT_APP), null);

    assertThat(service.listApps()).containsExactly("agent_app", "flow_app");
  }

  @Test
  void loadRoot_workflowApp_returnsTheWorkflow() {
    RunnerService service = runnerService(new InMemoryAppLoader(FLOW_APP), null);

    assertThat(service.loadRoot("flow_app")).hasValue(WORKFLOW);
  }

  @Test
  void loadRoot_agentApp_returnsTheRootAgent() {
    RunnerService service = runnerService(new InMemoryAppLoader(AGENT_APP), null);

    assertThat(service.loadRoot("agent_app")).hasValue(AGENT);
  }

  @Test
  void loadRoot_unknownApp_returnsEmpty() {
    RunnerService service = runnerService(new InMemoryAppLoader(FLOW_APP), null);

    assertThat(service.loadRoot("missing")).isEmpty();
  }

  @Test
  void loadRoot_appFailsToLoad_throwsIllegalStateException() {
    RunnerService service = runnerService(new FailingAppLoader(new IllegalStateException()), null);

    assertThrows(IllegalStateException.class, () -> service.loadRoot("broken"));
  }

  @Test
  void getRunner_appLoader_runsTheAppWithItsPluginsBeforeTheServers() {
    RunnerService service = runnerService(new InMemoryAppLoader(FLOW_APP), null, SERVER_PLUGIN);

    Runner runner = service.getRunner("flow_app");

    assertThat(runner.appName()).isEqualTo("flow_app");
    assertThat(runner.node()).hasValue(WORKFLOW);
    assertThat(runner.pluginManager().getPlugins())
        .containsExactly(APP_PLUGIN, SERVER_PLUGIN)
        .inOrder();
  }

  @Test
  void getRunner_appServedUnderAnotherName_runsItUnderTheRequestedName() {
    AppLoader loader =
        new AppLoader() {
          @Override
          public ImmutableList<String> listApps() {
            return ImmutableList.of("alias");
          }

          @Override
          public Optional<App> loadApp(String appName) {
            return appName.equals("alias") ? Optional.of(AGENT_APP) : Optional.empty();
          }
        };
    RunnerService service = runnerService(loader, null);

    assertThat(service.getRunner("alias").appName()).isEqualTo("alias");
  }

  @Test
  void getRunner_calledTwice_returnsTheCachedRunner() {
    RunnerService service = runnerService(new InMemoryAppLoader(FLOW_APP), null);

    assertThat(service.getRunner("flow_app")).isSameInstanceAs(service.getRunner("flow_app"));
  }

  @Test
  void getRunner_unknownApp_throwsNotFound() {
    RunnerService service = runnerService(new InMemoryAppLoader(FLOW_APP), null);

    ResponseStatusException e =
        assertThrows(ResponseStatusException.class, () -> service.getRunner("missing"));

    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(e.getReason()).isEqualTo("App not found.");
  }

  @Test
  void getRunner_appFailsToLoad_throwsServerError() {
    RunnerService service = runnerService(new FailingAppLoader(new IllegalStateException()), null);

    ResponseStatusException e =
        assertThrows(ResponseStatusException.class, () -> service.getRunner("broken"));

    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(e.getReason()).isEqualTo("App failed to load.");
  }

  @Test
  void getRunner_appLoaderBreaksItsContract_throwsServerError() {
    RunnerService service =
        runnerService(new FailingAppLoader(new IllegalArgumentException()), null);

    ResponseStatusException e =
        assertThrows(ResponseStatusException.class, () -> service.getRunner("broken"));

    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(e.getReason()).isEqualTo("App failed to load.");
  }

  @Test
  void getRunner_agentLoader_runsTheAgentWithoutAnAppUnderAnyName() {
    RunnerService service =
        runnerService(null, new SingleAgentLoader("support.bot"), SERVER_PLUGIN);

    Runner runner = service.getRunner("support.bot");

    assertThat(runner.appName()).isEqualTo("support.bot");
    assertThat(runner.agent()).isSameInstanceAs(AGENT);
    assertThat(runner.pluginManager().getPlugins()).containsExactly(SERVER_PLUGIN);
  }

  @Test
  void getRunner_agentLoaderUnknownName_throwsNotFound() {
    RunnerService service = runnerService(null, new SingleAgentLoader("support.bot"));

    ResponseStatusException e =
        assertThrows(ResponseStatusException.class, () -> service.getRunner("missing"));

    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(e.getReason()).isEqualTo("App not found.");
  }

  @Test
  void loadRoot_agentFailsToLoad_throwsIllegalStateException() {
    AgentLoader loader =
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
    RunnerService service = runnerService(null, loader);

    assertThrows(IllegalStateException.class, () -> service.loadRoot("broken"));
  }

  @Test
  void appLoaderConstructor_servesTheAppLoader() {
    RunnerService service =
        new RunnerService(
            new InMemoryAppLoader(FLOW_APP),
            new InMemoryArtifactService(),
            new InMemorySessionService(),
            new InMemoryMemoryService(),
            ImmutableList.of());

    assertThat(service.listApps()).containsExactly("flow_app");
    assertThat(service.loadRoot("flow_app")).hasValue(WORKFLOW);
  }

  @Test
  void agentLoaderConstructor_servesTheAgentLoader() {
    RunnerService service =
        new RunnerService(
            new SingleAgentLoader("support.bot"),
            new InMemoryArtifactService(),
            new InMemorySessionService(),
            new InMemoryMemoryService(),
            ImmutableList.of());

    assertThat(service.listApps()).containsExactly("support.bot");
    assertThat(service.loadRoot("support.bot")).hasValue(AGENT);
  }

  @Test
  void constructor_appLoaderAndCompiledLoaderWithAnAgent_hidesTheCompiledAgent() {
    CompiledAgentLoader compiledLoader =
        new CompiledAgentLoader(new AgentLoadingProperties()) {
          @Override
          public ImmutableList<String> listAgents() {
            return ImmutableList.of("compiled_agent");
          }

          @Override
          public BaseAgent loadAgent(String name) {
            return AGENT;
          }
        };
    RunnerService service = runnerService(new InMemoryAppLoader(FLOW_APP), compiledLoader);

    assertThat(service.listApps()).containsExactly("flow_app");
    ResponseStatusException e =
        assertThrows(ResponseStatusException.class, () -> service.getRunner("compiled_agent"));
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void constructor_appLoaderAndAnotherAgentLoader_throwsIllegalStateException() {
    assertThrows(
        IllegalStateException.class,
        () -> runnerService(new InMemoryAppLoader(FLOW_APP), new SingleAgentLoader("support.bot")));
  }

  @Test
  void constructor_appLoaderAndAnotherAgentLoaderNextToThePrimaryDefault_throwsIllegalState() {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerSingleton("appLoader", new InMemoryAppLoader(FLOW_APP));
    // Registered like the server's default loader: a @Primary bean definition.
    RootBeanDefinition compiled =
        new RootBeanDefinition(
            CompiledAgentLoader.class, () -> new CompiledAgentLoader(new AgentLoadingProperties()));
    compiled.setPrimary(true);
    beans.registerBeanDefinition("agentLoader", compiled);
    beans.registerSingleton("customAgentLoader", new SingleAgentLoader("support.bot"));

    assertThrows(
        IllegalStateException.class,
        () ->
            new RunnerService(
                beans.getBeanProvider(AppLoader.class),
                beans.getBeanProvider(AgentLoader.class),
                new InMemoryArtifactService(),
                new InMemorySessionService(),
                new InMemoryMemoryService(),
                ImmutableList.of()));
  }

  @Test
  void constructor_noLoader_throwsIllegalStateException() {
    assertThrows(IllegalStateException.class, () -> runnerService(null, null));
  }

  private static RunnerService runnerService(
      @Nullable AppLoader appLoader, @Nullable AgentLoader agentLoader, BasePlugin... plugins) {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    if (appLoader != null) {
      beans.registerSingleton("appLoader", appLoader);
    }
    if (agentLoader != null) {
      beans.registerSingleton("agentLoader", agentLoader);
    }
    return new RunnerService(
        beans.getBeanProvider(AppLoader.class),
        beans.getBeanProvider(AgentLoader.class),
        new InMemoryArtifactService(),
        new InMemorySessionService(),
        new InMemoryMemoryService(),
        ImmutableList.copyOf(plugins));
  }

  /** Serves {@link #AGENT} under one name. */
  private static final class SingleAgentLoader implements AgentLoader {
    private final String name;

    SingleAgentLoader(String name) {
      this.name = name;
    }

    @Override
    public ImmutableList<String> listAgents() {
      return ImmutableList.of(name);
    }

    @Override
    public BaseAgent loadAgent(String name) {
      if (!name.equals(this.name)) {
        throw new NoSuchElementException();
      }
      return AGENT;
    }
  }

  /** Lists one app that fails to load with {@code failure}. */
  private static final class FailingAppLoader implements AppLoader {
    private final RuntimeException failure;

    FailingAppLoader(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public ImmutableList<String> listApps() {
      return ImmutableList.of("broken");
    }

    @Override
    public Optional<App> loadApp(String appName) {
      throw failure;
    }
  }
}
