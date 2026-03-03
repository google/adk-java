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

package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class PluginManagerTest {

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  private final PluginManager pluginManager = new PluginManager();
  @Mock private Plugin plugin1;
  @Mock private Plugin plugin2;
  @Mock private InvocationContext mockInvocationContext;
  private final Content content = Content.builder().build();
  private final Session session = Session.builder("session_id").build();

  @Before
  public void setUp() {
    when(plugin1.getName()).thenReturn("plugin1");
    when(plugin2.getName()).thenReturn("plugin2");
    when(mockInvocationContext.session()).thenReturn(session);
  }

  @Test
  public void registerPlugin_success() {
    pluginManager.registerPlugin(plugin1);
    assertThat(pluginManager.getPlugin("plugin1")).isPresent();
  }

  @Test
  public void ctor_registerPlugin() {
    PluginManager manager = new PluginManager(ImmutableList.of(plugin1));
    assertThat(manager.getPlugin("plugin1")).isPresent();
  }

  @Test
  public void registerPlugin_duplicateName_throwsException() {
    pluginManager.registerPlugin(plugin1);
    assertThrows(IllegalArgumentException.class, () -> pluginManager.registerPlugin(plugin1));
  }

  @Test
  public void getPlugin_notFound() {
    assertThat(pluginManager.getPlugin("nonexistent")).isEmpty();
  }

  @Test
  public void onUserMessageCallback_noPlugins() {
    pluginManager.onUserMessageCallback(mockInvocationContext, content).test().assertResult();
  }

  @Test
  public void onUserMessageCallback_allReturnEmpty() {
    when(plugin1.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    when(plugin2.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.onUserMessageCallback(mockInvocationContext, content).test().assertResult();

    verify(plugin1).onUserMessageCallback(mockInvocationContext, content);
    verify(plugin2).onUserMessageCallback(mockInvocationContext, content);
  }

  @Test
  public void onUserMessageCallback_plugin1ReturnsValue_earlyExit() {
    Content expectedContent = Content.builder().build();
    when(plugin1.onUserMessageCallback(any(), any())).thenReturn(Maybe.just(expectedContent));
    when(plugin2.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager
        .onUserMessageCallback(mockInvocationContext, content)
        .test()
        .assertResult(expectedContent);

    verify(plugin1).onUserMessageCallback(mockInvocationContext, content);
    verify(plugin2, never()).onUserMessageCallback(any(), any());
  }

  @Test
  public void onUserMessageCallback_pluginOrderRespected() {
    Content expectedContent = Content.builder().build();
    when(plugin1.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    when(plugin2.onUserMessageCallback(any(), any())).thenReturn(Maybe.just(expectedContent));
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager
        .onUserMessageCallback(mockInvocationContext, content)
        .test()
        .assertResult(expectedContent);

    InOrder inOrder = inOrder(plugin1, plugin2);
    inOrder.verify(plugin1).onUserMessageCallback(mockInvocationContext, content);
    inOrder.verify(plugin2).onUserMessageCallback(mockInvocationContext, content);
  }

  @Test
  public void afterRunCallback_allComplete() {
    when(plugin1.afterRunCallback(any())).thenReturn(Completable.complete());
    when(plugin2.afterRunCallback(any())).thenReturn(Completable.complete());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.afterRunCallback(mockInvocationContext).test().assertResult();

    verify(plugin1).afterRunCallback(mockInvocationContext);
    verify(plugin2).afterRunCallback(mockInvocationContext);
  }

  @Test
  public void afterRunCallback_plugin1Fails() {
    RuntimeException testException = new RuntimeException("Test");
    when(plugin1.afterRunCallback(any())).thenReturn(Completable.error(testException));
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.afterRunCallback(mockInvocationContext).test().assertError(testException);

    verify(plugin1).afterRunCallback(mockInvocationContext);
    verify(plugin2, never()).afterRunCallback(any());
  }

  @Test
  public void beforeAgentCallback_plugin2ReturnsValue() {
    BaseAgent mockAgent = mock(BaseAgent.class);
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    Content expectedContent = Content.builder().build();

    when(plugin1.beforeAgentCallback(any(), any())).thenReturn(Maybe.empty());
    when(plugin2.beforeAgentCallback(any(), any())).thenReturn(Maybe.just(expectedContent));
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager
        .beforeAgentCallback(mockAgent, mockCallbackContext)
        .test()
        .assertResult(expectedContent);

    verify(plugin1).beforeAgentCallback(mockAgent, mockCallbackContext);
    verify(plugin2).beforeAgentCallback(mockAgent, mockCallbackContext);
  }

  @Test
  public void beforeRunCallback_singlePlugin() {
    Content expectedContent = Content.builder().build();

    when(plugin1.beforeRunCallback(any())).thenReturn(Maybe.just(expectedContent));
    pluginManager.registerPlugin(plugin1);

    pluginManager.beforeRunCallback(mockInvocationContext).test().assertResult(expectedContent);

    verify(plugin1).beforeRunCallback(mockInvocationContext);
  }

  @Test
  public void onEventCallback_singlePlugin() {
    Event mockEvent = mock(Event.class);
    when(plugin1.onEventCallback(any(), any())).thenReturn(Maybe.just(mockEvent));
    pluginManager.registerPlugin(plugin1);

    pluginManager.onEventCallback(mockInvocationContext, mockEvent).test().assertResult(mockEvent);

    verify(plugin1).onEventCallback(mockInvocationContext, mockEvent);
  }

  @Test
  public void afterAgentCallback_singlePlugin() {
    BaseAgent mockAgent = mock(BaseAgent.class);
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    Content expectedContent = Content.builder().build();

    when(plugin1.afterAgentCallback(any(), any())).thenReturn(Maybe.just(expectedContent));
    pluginManager.registerPlugin(plugin1);

    pluginManager
        .afterAgentCallback(mockAgent, mockCallbackContext)
        .test()
        .assertResult(expectedContent);

    verify(plugin1).afterAgentCallback(mockAgent, mockCallbackContext);
  }

  @Test
  public void beforeModelCallback_singlePlugin() {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    LlmResponse llmResponse = LlmResponse.builder().build();

    when(plugin1.beforeModelCallback(any(), any())).thenReturn(Maybe.just(llmResponse));
    pluginManager.registerPlugin(plugin1);

    pluginManager
        .beforeModelCallback(mockCallbackContext, llmRequestBuilder)
        .test()
        .assertResult(llmResponse);

    verify(plugin1).beforeModelCallback(mockCallbackContext, llmRequestBuilder);
  }

  @Test
  public void afterModelCallback_singlePlugin() {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    LlmResponse llmResponse = LlmResponse.builder().build();

    when(plugin1.afterModelCallback(any(), any())).thenReturn(Maybe.just(llmResponse));
    pluginManager.registerPlugin(plugin1);

    pluginManager
        .afterModelCallback(mockCallbackContext, llmResponse)
        .test()
        .assertResult(llmResponse);

    verify(plugin1).afterModelCallback(mockCallbackContext, llmResponse);
  }

  @Test
  public void onModelErrorCallback_singlePlugin() {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    Throwable mockThrowable = mock(Throwable.class);
    LlmResponse llmResponse = LlmResponse.builder().build();

    when(plugin1.onModelErrorCallback(any(), any(), any())).thenReturn(Maybe.just(llmResponse));
    pluginManager.registerPlugin(plugin1);

    pluginManager
        .onModelErrorCallback(mockCallbackContext, llmRequestBuilder, mockThrowable)
        .test()
        .assertResult(llmResponse);

    verify(plugin1).onModelErrorCallback(mockCallbackContext, llmRequestBuilder, mockThrowable);
  }

  @Test
  public void beforeToolCallback_singlePlugin() {
    BaseTool mockTool = mock(BaseTool.class);
    ImmutableMap<String, Object> toolArgs = ImmutableMap.of();
    ToolContext mockToolContext = mock(ToolContext.class);

    when(plugin1.beforeToolCallback(any(), any(), any())).thenReturn(Maybe.just(toolArgs));
    pluginManager.registerPlugin(plugin1);

    pluginManager
        .beforeToolCallback(mockTool, toolArgs, mockToolContext)
        .test()
        .assertResult(toolArgs);

    verify(plugin1).beforeToolCallback(mockTool, toolArgs, mockToolContext);
  }

  @Test
  public void afterToolCallback_singlePlugin() {
    BaseTool mockTool = mock(BaseTool.class);
    ImmutableMap<String, Object> toolArgs = ImmutableMap.of();
    ToolContext mockToolContext = mock(ToolContext.class);
    ImmutableMap<String, Object> result = ImmutableMap.of();

    when(plugin1.afterToolCallback(any(), any(), any(), any())).thenReturn(Maybe.just(result));
    pluginManager.registerPlugin(plugin1);

    pluginManager
        .afterToolCallback(mockTool, toolArgs, mockToolContext, result)
        .test()
        .assertResult(result);

    verify(plugin1).afterToolCallback(mockTool, toolArgs, mockToolContext, result);
  }

  @Test
  public void onToolErrorCallback_singlePlugin() {
    BaseTool mockTool = mock(BaseTool.class);
    ImmutableMap<String, Object> toolArgs = ImmutableMap.of();
    ToolContext mockToolContext = mock(ToolContext.class);
    Throwable mockThrowable = mock(Throwable.class);
    ImmutableMap<String, Object> result = ImmutableMap.of();

    when(plugin1.onToolErrorCallback(any(), any(), any(), any())).thenReturn(Maybe.just(result));
    pluginManager.registerPlugin(plugin1);
    pluginManager
        .onToolErrorCallback(mockTool, toolArgs, mockToolContext, mockThrowable)
        .test()
        .assertResult(result);

    verify(plugin1).onToolErrorCallback(mockTool, toolArgs, mockToolContext, mockThrowable);
  }

  @Test
  public void close_allComplete() {
    when(plugin1.close()).thenReturn(Completable.complete());
    when(plugin2.close()).thenReturn(Completable.complete());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.close().test().assertResult();

    verify(plugin1).close();
    verify(plugin2).close();
  }

  @Test
  public void close_plugin1Fails() {
    RuntimeException testException = new RuntimeException("Test");
    when(plugin1.close()).thenReturn(Completable.error(testException));
    when(plugin2.close()).thenReturn(Completable.complete());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.close().test().assertError(testException);

    verify(plugin1).close();
    verify(plugin2).close();
  }
}
