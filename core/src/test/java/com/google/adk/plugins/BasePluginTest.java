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

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class BasePluginTest {

  private static class TestPlugin extends BasePlugin {
    TestPlugin() {
      super("TestPlugin");
    }
  }

  private final BasePlugin plugin = new TestPlugin();
  private final InvocationContext invocationContext = Mockito.mock(InvocationContext.class);
  private final CallbackContext callbackContext = Mockito.mock(CallbackContext.class);
  private final Content content = Content.builder().build();
  private final Event event = Mockito.mock(Event.class);
  private final LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
  private final LlmResponse llmResponse = LlmResponse.builder().build();
  private final ToolContext toolContext = Mockito.mock(ToolContext.class);

  @Test
  public void onUserMessageCallback_returnsEmptyMaybe() {
    plugin.onUserMessageCallback(invocationContext, content).test().assertResult();
  }

  @Test
  public void beforeRunCallback_returnsEmptyMaybe() {
    plugin.beforeRunCallback(invocationContext).test().assertResult();
  }

  @Test
  public void onEventCallback_returnsEmptyMaybe() {
    plugin.onEventCallback(invocationContext, event).test().assertResult();
  }

  @Test
  public void afterRunCallback_returnsCompletedCompletable() {
    plugin.afterRunCallback(invocationContext).test().assertResult();
  }

  @Test
  public void beforeAgentCallback_returnsEmptyMaybe() {
    plugin.beforeAgentCallback(null, callbackContext).test().assertResult();
  }

  @Test
  public void afterAgentCallback_returnsEmptyMaybe() {
    plugin.afterAgentCallback(null, callbackContext).test().assertResult();
  }

  @Test
  public void beforeModelCallback_returnsEmptyMaybe() {
    plugin.beforeModelCallback(callbackContext, llmRequestBuilder).test().assertResult();
  }

  @Test
  public void afterModelCallback_returnsEmptyMaybe() {
    plugin.afterModelCallback(callbackContext, llmResponse).test().assertResult();
  }

  @Test
  public void onModelErrorCallback_returnsEmptyMaybe() {
    plugin
        .onModelErrorCallback(callbackContext, llmRequestBuilder, new RuntimeException())
        .test()
        .assertResult();
  }

  @Test
  public void beforeToolCallback_returnsEmptyMaybe() {
    plugin.beforeToolCallback(null, new HashMap<>(), toolContext).test().assertResult();
  }

  @Test
  public void afterToolCallback_returnsEmptyMaybe() {
    plugin
        .afterToolCallback(null, new HashMap<>(), toolContext, new HashMap<>())
        .test()
        .assertResult();
  }

  @Test
  public void onToolErrorCallback_returnsEmptyMaybe() {
    plugin
        .onToolErrorCallback(null, new HashMap<>(), toolContext, new RuntimeException())
        .test()
        .assertResult();
  }
}
