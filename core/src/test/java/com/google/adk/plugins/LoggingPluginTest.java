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

import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class LoggingPluginTest {

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  private final LoggingPlugin loggingPlugin = new LoggingPlugin();
  @Mock private InvocationContext mockInvocationContext;
  @Mock private BaseAgent mockAgent;
  @Mock private CallbackContext mockCallbackContext;
  @Mock private BaseTool mockTool;
  @Mock private ToolContext mockToolContext;

  private final Content content = Content.builder().build();
  private final Session session = Session.builder("session_id").build();
  private final Event event =
      Event.builder()
          .id("event_id")
          .author("author")
          .content(Optional.empty())
          .actions(EventActions.builder().build())
          .longRunningToolIds(Optional.empty())
          .build();
  private final LlmRequest.Builder llmRequestBuilder =
      LlmRequest.builder().model("default").contents(ImmutableList.of());
  private final LlmResponse llmResponse = LlmResponse.builder().build();
  private final ImmutableMap<String, Object> toolArgs = ImmutableMap.of();
  private final ImmutableMap<String, Object> toolResult = ImmutableMap.of();
  private final Throwable throwable = new RuntimeException("Test Error");

  @Before
  public void setUp() {
    when(mockInvocationContext.session()).thenReturn(session);
    when(mockInvocationContext.agent()).thenReturn(mockAgent);
    when(mockInvocationContext.invocationId()).thenReturn("invocation_id");
    when(mockInvocationContext.userId()).thenReturn("user_id");
    when(mockInvocationContext.appName()).thenReturn("app_name");
    when(mockInvocationContext.branch()).thenReturn(Optional.empty());

    when(mockCallbackContext.invocationId()).thenReturn("invocation_id");
    when(mockCallbackContext.agentName()).thenReturn("agent_name");
    when(mockCallbackContext.invocationContext()).thenReturn(mockInvocationContext);

    when(mockTool.name()).thenReturn("tool_name");
    when(mockToolContext.agentName()).thenReturn("agent_name");
    when(mockToolContext.functionCallId()).thenReturn(Optional.empty());
  }

  @Test
  public void onUserMessageCallback_runsWithoutError() {
    loggingPlugin.onUserMessageCallback(mockInvocationContext, content).test().assertComplete();
  }

  @Test
  public void beforeRunCallback_runsWithoutError() {
    loggingPlugin.beforeRunCallback(mockInvocationContext).test().assertComplete();
  }

  @Test
  public void onEventCallback_runsWithoutError() {
    loggingPlugin.onEventCallback(mockInvocationContext, event).test().assertComplete();
  }

  @Test
  public void onEventCallback_functionCalls() {
    loggingPlugin
        .onEventCallback(
            mockInvocationContext,
            Event.builder()
                .id("id")
                .content(
                    Content.builder()
                        .parts(
                            Part.builder()
                                .functionCall(FunctionCall.builder().name("function").build())
                                .build())
                        .build())
                .build())
        .test()
        .assertComplete();
  }

  @Test
  public void onEventCallback_functionResponses() {
    loggingPlugin
        .onEventCallback(
            mockInvocationContext,
            Event.builder()
                .id("id")
                .content(
                    Content.builder()
                        .parts(
                            Part.builder()
                                .functionResponse(
                                    FunctionResponse.builder().name("function").build())
                                .build())
                        .build())
                .build())
        .test()
        .assertComplete();
  }

  @Test
  public void onEventCallback_longRunningToolId() {
    loggingPlugin
        .onEventCallback(
            mockInvocationContext,
            Event.builder().id("id").longRunningToolIds(ImmutableSet.of("123")).build())
        .test()
        .assertComplete();
  }

  @Test
  public void afterRunCallback_runsWithoutError() {
    loggingPlugin.afterRunCallback(mockInvocationContext).test().assertComplete();
  }

  @Test
  public void beforeAgentCallback_runsWithoutError() {
    loggingPlugin.beforeAgentCallback(mockAgent, mockCallbackContext).test().assertComplete();
  }

  @Test
  public void afterAgentCallback_runsWithoutError() {
    loggingPlugin.afterAgentCallback(mockAgent, mockCallbackContext).test().assertComplete();
  }

  @Test
  public void beforeModelCallback_runsWithoutError() {
    loggingPlugin
        .beforeModelCallback(mockCallbackContext, llmRequestBuilder)
        .test()
        .assertComplete();
  }

  @Test
  public void beforeModelCallback_longSystemInstruction() {
    loggingPlugin
        .beforeModelCallback(
            mockCallbackContext,
            LlmRequest.builder()
                .appendInstructions(ImmutableList.of("all work and no play".repeat(1000))))
        .test()
        .assertComplete();
  }

  @Test
  public void beforeModelCallback_tools() {
    loggingPlugin
        .beforeModelCallback(
            mockCallbackContext, LlmRequest.builder().appendTools(ImmutableList.of(mockTool)))
        .test()
        .assertComplete();
  }

  @Test
  public void afterModelCallback_runsWithoutError() {
    loggingPlugin.afterModelCallback(mockCallbackContext, llmResponse).test().assertComplete();
  }

  @Test
  public void afterModelCallback_errorCode() {
    loggingPlugin
        .afterModelCallback(
            mockCallbackContext,
            LlmResponse.builder().errorCode(new FinishReason(FinishReason.Known.SAFETY)).build())
        .test()
        .assertComplete();
  }

  @Test
  public void afterModelCallback_usageMetadata() {
    loggingPlugin
        .afterModelCallback(
            mockCallbackContext,
            LlmResponse.builder()
                .usageMetadata(
                    GenerateContentResponseUsageMetadata.builder().promptTokenCount(123).build())
                .build())
        .test()
        .assertComplete();
  }

  @Test
  public void onModelErrorCallback_runsWithoutError() {
    loggingPlugin
        .onModelErrorCallback(mockCallbackContext, llmRequestBuilder, throwable)
        .test()
        .assertComplete();
  }

  @Test
  public void beforeToolCallback_runsWithoutError() {
    loggingPlugin.beforeToolCallback(mockTool, toolArgs, mockToolContext).test().assertComplete();
  }

  @Test
  public void afterToolCallback_runsWithoutError() {
    loggingPlugin
        .afterToolCallback(mockTool, toolArgs, mockToolContext, toolResult)
        .test()
        .assertComplete();
  }

  @Test
  public void onToolErrorCallback_runsWithoutError() {
    loggingPlugin
        .onToolErrorCallback(mockTool, toolArgs, mockToolContext, throwable)
        .test()
        .assertComplete();
  }
}
