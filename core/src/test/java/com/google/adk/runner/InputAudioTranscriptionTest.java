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
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Content;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class InputAudioTranscriptionTest {

  private Content createContent(String text) {
    return Content.builder().parts(Part.builder().text(text).build()).build();
  }

  private InvocationContext invokeNewInvocationContextForLive(
      Runner runner, Session session, LiveRequestQueue liveRequestQueue, RunConfig runConfig)
      throws Exception {
    Method method =
        Runner.class.getDeclaredMethod(
            "newInvocationContextForLive", Session.class, Optional.class, RunConfig.class);
    method.setAccessible(true);
    return (InvocationContext)
        method.invoke(runner, session, Optional.of(liveRequestQueue), runConfig);
  }

  @Test
  public void newInvocationContextForLive_autoConfiguresInputAudioTranscription() throws Exception {
    TestLlm testLlm = createTestLlm(createLlmResponse(createContent("response")));
    LlmAgent subAgent = createTestAgentBuilder(testLlm).name("sub_agent").build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent))
            .build();

    Runner runner = new InMemoryRunner(rootAgent, "test", ImmutableList.of());
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    RunConfig initialConfig =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setStreamingMode(RunConfig.StreamingMode.BIDI)
            .build();

    assertThat(initialConfig.inputAudioTranscription()).isNull();

    LiveRequestQueue liveQueue = new LiveRequestQueue();
    InvocationContext context =
        invokeNewInvocationContextForLive(runner, session, liveQueue, initialConfig);

    assertThat(context.runConfig().inputAudioTranscription()).isNotNull();
  }

  @Test
  public void newInvocationContextForLive_multiAgent_preservesUserInputAudioTranscription()
      throws Exception {
    TestLlm testLlm = createTestLlm(createLlmResponse(createContent("response")));
    LlmAgent subAgent = createTestAgentBuilder(testLlm).name("sub_agent").build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent))
            .build();

    Runner runner = new InMemoryRunner(rootAgent, "test", ImmutableList.of());
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    AudioTranscriptionConfig userConfig = AudioTranscriptionConfig.builder().build();
    RunConfig configWithUserSetting =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setStreamingMode(RunConfig.StreamingMode.BIDI)
            .setInputAudioTranscription(userConfig)
            .build();

    LiveRequestQueue liveQueue = new LiveRequestQueue();
    InvocationContext context =
        invokeNewInvocationContextForLive(runner, session, liveQueue, configWithUserSetting);

    assertThat(context.runConfig().inputAudioTranscription()).isSameInstanceAs(userConfig);
  }

  @Test
  public void newInvocationContextForLive_singleAgent_autoConfiguresInputAudioTranscription()
      throws Exception {
    TestLlm testLlm = createTestLlm(createLlmResponse(createContent("response")));
    // Single agent with NO sub-agents
    LlmAgent singleAgent = createTestAgentBuilder(testLlm).name("weather_agent").build();

    Runner runner = new InMemoryRunner(singleAgent, "test", ImmutableList.of());
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    RunConfig initialConfig =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setStreamingMode(RunConfig.StreamingMode.BIDI)
            .build();

    assertThat(initialConfig.inputAudioTranscription()).isNull();

    LiveRequestQueue liveQueue = new LiveRequestQueue();
    InvocationContext context =
        invokeNewInvocationContextForLive(runner, session, liveQueue, initialConfig);

    assertThat(context.runConfig().inputAudioTranscription()).isNotNull();
  }

  @Test
  public void newInvocationContextForLive_singleAgent_preservesUserInputAudioTranscription()
      throws Exception {
    TestLlm testLlm = createTestLlm(createLlmResponse(createContent("response")));
    // Single agent with NO sub-agents
    LlmAgent singleAgent = createTestAgentBuilder(testLlm).name("weather_agent").build();

    Runner runner = new InMemoryRunner(singleAgent, "test", ImmutableList.of());
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    AudioTranscriptionConfig userConfig = AudioTranscriptionConfig.builder().build();
    RunConfig configWithUserSetting =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setStreamingMode(RunConfig.StreamingMode.BIDI)
            .setInputAudioTranscription(userConfig)
            .build();

    LiveRequestQueue liveQueue = new LiveRequestQueue();
    InvocationContext context =
        invokeNewInvocationContextForLive(runner, session, liveQueue, configWithUserSetting);

    assertThat(context.runConfig().inputAudioTranscription()).isSameInstanceAs(userConfig);
  }
}
