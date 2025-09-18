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

package com.google.adk.runner;

import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.testing.TestUtils.FailingEchoTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class RunnerTest {

  private final BasePlugin plugin = mockPlugin("test");
  private final Content pluginContent = createContent("from plugin");
  private final TestLlm testLlm = createTestLlm(createLlmResponse(createContent("from llm")));
  private final LlmAgent agent = createTestAgentBuilder(testLlm).build();
  private final Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin));
  private final Session session =
      runner.sessionService().createSession("test", "user").blockingGet();

  private final FailingEchoTool failingEchoTool = new FailingEchoTool();
  private final EchoTool echoTool = new EchoTool();

  private final TestLlm testLlmWithFunctionCall =
      createTestLlm(
          createLlmResponse(
              Content.builder()
                  .role("model")
                  .parts(
                      Part.builder()
                          .functionCall(
                              FunctionCall.builder()
                                  // Note: echoTool and failingEchoTool have the same name name
                                  .name(echoTool.name())
                                  .args(ImmutableMap.of("args_name", "args_value"))
                                  .build())
                          .build())
                  .build()),
          createLlmResponse(createContent("done")));

  private BasePlugin mockPlugin(String name) {
    // Need CALLS_REAL_METHODS to avoid NPE. The default implementation is only returning
    // Maybe.empty()
    BasePlugin plugin = mock(BasePlugin.class, CALLS_REAL_METHODS);
    when(plugin.getName()).thenReturn(name);
    return plugin;
  }

  @Test
  public void pluginDoesNothing() {
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
  }

  @Test
  public void beforeRunCallback_success() {
    when(plugin.beforeRunCallback(any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner
            .runAsync("user", session.id(), createContent("will not be processed"))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("model: from plugin");
  }

  @Test
  public void beforeRunCallback_error() {
    Exception exception = new Exception("test");
    when(plugin.beforeRunCallback(any())).thenReturn(Maybe.error(exception));

    runner
        .runAsync("user", session.id(), createContent("will not be processed"))
        .test()
        .assertError(exception);
  }

  @Test
  public void beforeRunCallback_multiplePluginsFirstOnly() {
    BasePlugin plugin1 = mockPlugin("test1");
    when(plugin1.beforeRunCallback(any())).thenReturn(Maybe.just(pluginContent));
    BasePlugin plugin2 = mockPlugin("test2");
    when(plugin2.beforeRunCallback(any())).thenReturn(Maybe.empty());

    Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin1, plugin2));
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner
            .runAsync("user", session.id(), createContent("will not be processed"))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("model: from plugin");
    verify(plugin2, never()).beforeRunCallback(any());
  }

  @Test
  public void afterRunCallback_success() {
    when(plugin.afterRunCallback(any())).thenReturn(Completable.complete());

    var events =
        runner
            .runAsync("user", session.id(), createContent("will not be processed"))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
    verify(plugin).afterRunCallback(any());
  }

  @Test
  public void afterRunCallback_error() {
    Exception exception = new Exception("test");

    when(plugin.afterRunCallback(any())).thenReturn(Completable.error(exception));

    runner
        .runAsync("user", session.id(), createContent("will not be processed"))
        .test()
        .assertError(exception);

    verify(plugin).afterRunCallback(any());
  }

  @Test
  public void onUserMessageCallback_success() {
    when(plugin.onUserMessageCallback(any(), any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
    ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
    verify(plugin).onUserMessageCallback(any(), contentCaptor.capture());
    assertThat(contentCaptor.getValue().parts().get().get(0).text()).hasValue("from user");
  }

  @Test
  public void beforeAgentCallback_success() {
    when(plugin.beforeAgentCallback(any(), any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).beforeAgentCallback(any(), any());
  }

  @Test
  public void afterAgentCallback_success() {
    when(plugin.afterAgentCallback(any(), any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly("test agent: from llm", "test agent: from plugin");
    verify(plugin).afterAgentCallback(any(), any());
  }

  @Test
  public void beforeModelCallback_success() {
    LlmResponse pluginResponse = createLlmResponse(createContent("from plugin"));

    when(plugin.beforeModelCallback(any(), any())).thenReturn(Maybe.just(pluginResponse));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).beforeModelCallback(any(), any());
  }

  @Test
  public void afterModelCallback_success() {
    LlmResponse pluginResponse = createLlmResponse(createContent("from plugin"));

    when(plugin.afterModelCallback(any(), any())).thenReturn(Maybe.just(pluginResponse));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).afterModelCallback(any(), any());
  }

  @Test
  public void onModelErrorCallback_success() {
    Exception exception = new Exception("test");
    LlmResponse pluginResponse = createLlmResponse(createContent("from plugin"));

    when(plugin.onModelErrorCallback(any(), any(), any())).thenReturn(Maybe.just(pluginResponse));

    TestLlm failingTestLlm = createTestLlm(Flowable.error(exception));
    LlmAgent agent = createTestAgentBuilder(failingTestLlm).build();

    Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin));
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).onModelErrorCallback(any(), any(), any());
  }

  @Test
  public void onModelErrorCallback_error() {
    Exception exception = new Exception("test");

    when(plugin.onModelErrorCallback(any(), any(), any())).thenReturn(Maybe.empty());

    TestLlm failingTestLlm = createTestLlm(Flowable.error(exception));
    LlmAgent agent = createTestAgentBuilder(failingTestLlm).build();

    Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin));
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    runner.runAsync("user", session.id(), createContent("from user")).test().assertError(exception);

    verify(plugin).onModelErrorCallback(any(), any(), any());
  }

  @Test
  public void beforeToolCallback_success() {
    ImmutableMap<String, Object> pluginResponse = ImmutableMap.of("result", "from plugin");

    when(plugin.beforeToolCallback(any(), any(), any())).thenReturn(Maybe.just(pluginResponse));

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall)
            .tools(ImmutableList.of(failingEchoTool))
            .build();

    Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin));
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly(
            "test agent: FunctionCall(name=echo_tool, args={args_name=args_value})",
            "test agent: FunctionResponse(name=echo_tool, response={result=from plugin})",
            "test agent: done");
    verify(plugin).beforeToolCallback(any(), any(), any());
  }

  @Test
  public void afterToolCallback_success() {
    ImmutableMap<String, Object> pluginResponse = ImmutableMap.of("result", "from plugin");

    when(plugin.afterToolCallback(any(), any(), any(), any()))
        .thenReturn(Maybe.just(pluginResponse));

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall).tools(ImmutableList.of(echoTool)).build();

    Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin));
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly(
            "test agent: FunctionCall(name=echo_tool, args={args_name=args_value})",
            "test agent: FunctionResponse(name=echo_tool, response={result=from plugin})",
            "test agent: done");
    verify(plugin).afterToolCallback(any(), any(), any(), any());
  }

  @Test
  public void onToolErrorCallback_success() {
    ImmutableMap<String, Object> pluginResponse = ImmutableMap.of("result", "from plugin");

    when(plugin.onToolErrorCallback(any(), any(), any(), any()))
        .thenReturn(Maybe.just(pluginResponse));

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall)
            .tools(ImmutableList.of(failingEchoTool))
            .build();

    Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin));
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly(
            "test agent: FunctionCall(name=echo_tool, args={args_name=args_value})",
            "test agent: FunctionResponse(name=echo_tool, response={result=from plugin})",
            "test agent: done");
    verify(plugin).onToolErrorCallback(any(), any(), any(), any());
  }

  @Test
  public void onToolErrorCallback_error() {
    when(plugin.onToolErrorCallback(any(), any(), any(), any())).thenReturn(Maybe.empty());

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall)
            .tools(ImmutableList.of(failingEchoTool))
            .build();

    Runner runner = new InMemoryRunner(agent, "test", ImmutableList.of(plugin));
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    runner
        .runAsync("user", session.id(), createContent("from user"))
        .test()
        .assertError(RuntimeException.class);

    verify(plugin).onToolErrorCallback(any(), any(), any(), any());
  }

  @Test
  public void onEventCallback_success() {
    when(plugin.onEventCallback(any(), any()))
        .thenReturn(Maybe.just(TestUtils.createEvent("form plugin")));

    List<Event> events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("author: content for event form plugin");

    verify(plugin).onEventCallback(any(), any());
  }

  private Content createContent(String text) {
    return Content.builder().parts(Part.builder().text(text).build()).build();
  }
}
