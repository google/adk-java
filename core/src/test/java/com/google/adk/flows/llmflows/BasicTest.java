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

package com.google.adk.flows.llmflows;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Modality;
import com.google.genai.types.Schema;
import com.google.genai.types.SpeechConfig;
import com.google.genai.types.VoiceConfig;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BasicTest {

  private static final String TEST_MODEL_NAME = "test-llm";
  private static final Schema TEST_OUTPUT_SCHEMA =
      Schema.builder().type("STRING").description("test schema").build();
  private static final GenerateContentConfig TEST_GEN_CONFIG =
      GenerateContentConfig.builder().temperature(0.5f).build();

  private static final SpeechConfig TEST_SPEECH_CONFIG =
      SpeechConfig.builder()
          .voiceConfig(VoiceConfig.builder().build())
          .languageCode("en-TEST")
          .build();
  private static final AudioTranscriptionConfig TEST_AUDIO_TRANSCRIPTION_CONFIG =
      AudioTranscriptionConfig.builder().build();

  private Basic basicProcessor;
  private TestLlm testLlm;
  private LlmAgent testAgent;
  private InvocationContext testContext;
  private LlmRequest initialRequest;

  @Before
  public void setUp() {
    basicProcessor = new Basic();
    testLlm = createTestLlm(new LlmResponse[] {});
    testAgent = createTestAgent(testLlm);
    testContext = createInvocationContext(testAgent);
    initialRequest = LlmRequest.builder().build();
  }

  @Test
  public void processRequest_populatesBasicFields() {
    RequestProcessingResult result =
        basicProcessor.processRequest(testContext, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.model()).hasValue(TEST_MODEL_NAME);
    assertThat(updatedRequest.config()).isPresent();
    assertThat(updatedRequest.config().get().temperature())
        .isNotEqualTo(TEST_GEN_CONFIG.temperature());
    assertThat(updatedRequest.liveConnectConfig()).isNotNull();
    assertThat(updatedRequest.liveConnectConfig().responseModalities().get()).isEmpty();
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_usesAgentConfigAndSchema() {
    LlmAgent agentWithConfig =
        LlmAgent.builder()
            .name("agentWithConfig")
            .model(testLlm)
            .generateContentConfig(TEST_GEN_CONFIG)
            .outputSchema(TEST_OUTPUT_SCHEMA)
            .build();
    InvocationContext contextWithConfig = createInvocationContext(agentWithConfig);

    RequestProcessingResult result =
        basicProcessor.processRequest(contextWithConfig, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.model()).hasValue(testLlm.model());
    assertThat(updatedRequest.config()).isPresent();
    assertThat(updatedRequest.config().get().temperature())
        .isEqualTo(TEST_GEN_CONFIG.temperature());

    assertThat(updatedRequest.config().get().responseSchema()).hasValue(TEST_OUTPUT_SCHEMA);
    assertThat(updatedRequest.config().get().responseMimeType()).hasValue("application/json");
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_buildsLiveConnectConfigFromRunConfig() {
    RunConfig runConfig =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.TEXT)))
            .build();
    LlmAgent agentWithConfig = LlmAgent.builder().name("agentWithConfig").model(testLlm).build();
    InvocationContext contextWithRunConfig = createInvocationContext(agentWithConfig, runConfig);

    RequestProcessingResult result =
        basicProcessor.processRequest(contextWithRunConfig, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.liveConnectConfig()).isNotNull();
    assertThat(updatedRequest.liveConnectConfig().responseModalities().get())
        .containsExactly(new Modality(Modality.Known.TEXT));
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_wrongAgentType_throwsIllegalArgumentException() {

    BaseAgent nonAgent =
        new BaseAgent("nonAgent", "desc", ImmutableList.of(), null, null) {
          @Override
          protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.empty();
          }

          @Override
          protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
          }
        };
    InvocationContext contextWithWrongAgent = createInvocationContext(nonAgent);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                basicProcessor.processRequest(contextWithWrongAgent, initialRequest).blockingGet());

    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("Agent in InvocationContext is not an instance of Agent.");
  }

  @Test
  public void processRequest_buildsLiveConnectConfigFromRunConfig_responseModalities() {
    RunConfig runConfig =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.TEXT)))
            .build();
    LlmAgent agentWithConfig = LlmAgent.builder().name("agentWithConfig").model(testLlm).build();
    InvocationContext contextWithRunConfig = createInvocationContext(agentWithConfig, runConfig);

    RequestProcessingResult result =
        basicProcessor.processRequest(contextWithRunConfig, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.liveConnectConfig()).isNotNull();
    assertThat(updatedRequest.liveConnectConfig().responseModalities().get())
        .containsExactly(new Modality(Modality.Known.TEXT));
    assertThat(updatedRequest.liveConnectConfig().speechConfig()).isEmpty();
    assertThat(updatedRequest.liveConnectConfig().outputAudioTranscription()).isEmpty();
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_buildsLiveConnectConfigFromRunConfig_speechConfig() {
    RunConfig runConfig = RunConfig.builder().setSpeechConfig(TEST_SPEECH_CONFIG).build();
    LlmAgent agentWithConfig = LlmAgent.builder().name("agentWithConfig").model(testLlm).build();
    InvocationContext contextWithRunConfig = createInvocationContext(agentWithConfig, runConfig);

    RequestProcessingResult result =
        basicProcessor.processRequest(contextWithRunConfig, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.liveConnectConfig()).isNotNull();
    assertThat(updatedRequest.liveConnectConfig().responseModalities().get()).isEmpty();
    assertThat(updatedRequest.liveConnectConfig().speechConfig()).hasValue(TEST_SPEECH_CONFIG);
    assertThat(updatedRequest.liveConnectConfig().outputAudioTranscription()).isEmpty();
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_buildsLiveConnectConfigFromRunConfig_outputAudioTranscription() {
    RunConfig runConfig =
        RunConfig.builder().setOutputAudioTranscription(TEST_AUDIO_TRANSCRIPTION_CONFIG).build();
    LlmAgent agentWithConfig = LlmAgent.builder().name("agentWithConfig").model(testLlm).build();
    InvocationContext contextWithRunConfig = createInvocationContext(agentWithConfig, runConfig);

    RequestProcessingResult result =
        basicProcessor.processRequest(contextWithRunConfig, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.liveConnectConfig()).isNotNull();
    assertThat(updatedRequest.liveConnectConfig().responseModalities().get()).isEmpty();
    assertThat(updatedRequest.liveConnectConfig().speechConfig()).isEmpty();
    assertThat(updatedRequest.liveConnectConfig().outputAudioTranscription())
        .hasValue(TEST_AUDIO_TRANSCRIPTION_CONFIG);
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_buildsLiveConnectConfigFromRunConfig_inputAudioTranscription() {
    RunConfig runConfig =
        RunConfig.builder().setInputAudioTranscription(TEST_AUDIO_TRANSCRIPTION_CONFIG).build();
    LlmAgent agentWithConfig = LlmAgent.builder().name("agentWithConfig").model(testLlm).build();
    InvocationContext contextWithRunConfig = createInvocationContext(agentWithConfig, runConfig);

    RequestProcessingResult result =
        basicProcessor.processRequest(contextWithRunConfig, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.liveConnectConfig()).isNotNull();
    assertThat(updatedRequest.liveConnectConfig().responseModalities().get()).isEmpty();
    assertThat(updatedRequest.liveConnectConfig().speechConfig()).isEmpty();
    assertThat(updatedRequest.liveConnectConfig().inputAudioTranscription())
        .hasValue(TEST_AUDIO_TRANSCRIPTION_CONFIG);
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_buildsLiveConnectConfigFromRunConfig_allFields() {
    RunConfig runConfig =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setSpeechConfig(TEST_SPEECH_CONFIG)
            .setOutputAudioTranscription(TEST_AUDIO_TRANSCRIPTION_CONFIG)
            .setInputAudioTranscription(TEST_AUDIO_TRANSCRIPTION_CONFIG)
            .build();
    LlmAgent agentWithConfig = LlmAgent.builder().name("agentWithConfig").model(testLlm).build();
    InvocationContext contextWithRunConfig = createInvocationContext(agentWithConfig, runConfig);

    RequestProcessingResult result =
        basicProcessor.processRequest(contextWithRunConfig, initialRequest).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.liveConnectConfig()).isNotNull();
    assertThat(updatedRequest.liveConnectConfig().responseModalities().get())
        .containsExactly(new Modality(Modality.Known.AUDIO));
    assertThat(updatedRequest.liveConnectConfig().speechConfig()).hasValue(TEST_SPEECH_CONFIG);
    assertThat(updatedRequest.liveConnectConfig().outputAudioTranscription())
        .hasValue(TEST_AUDIO_TRANSCRIPTION_CONFIG);
    assertThat(updatedRequest.liveConnectConfig().inputAudioTranscription())
        .hasValue(TEST_AUDIO_TRANSCRIPTION_CONFIG);
    assertThat(result.events()).isEmpty();
  }
}
