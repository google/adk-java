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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Modality;
import com.google.genai.types.SpeechConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RunConfigTest {

  @Test
  public void testBuilderWithVariousValues() {
    SpeechConfig speechConfig = SpeechConfig.builder().build();
    AudioTranscriptionConfig audioTranscriptionConfig = AudioTranscriptionConfig.builder().build();

    RunConfig runConfig =
        RunConfig.builder()
            .setSpeechConfig(speechConfig)
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.TEXT)))
            .setSaveInputBlobsAsArtifacts(true)
            .setStreamingMode(RunConfig.StreamingMode.SSE)
            .setOutputAudioTranscription(audioTranscriptionConfig)
            .setInputAudioTranscription(audioTranscriptionConfig)
            .setMaxLlmCalls(10)
            .build();

    assertThat(runConfig.speechConfig()).isEqualTo(speechConfig);
    assertThat(runConfig.responseModalities()).containsExactly(new Modality(Modality.Known.TEXT));
    assertThat(runConfig.saveInputBlobsAsArtifacts()).isTrue();
    assertThat(runConfig.streamingMode()).isEqualTo(RunConfig.StreamingMode.SSE);
    assertThat(runConfig.outputAudioTranscription()).isEqualTo(audioTranscriptionConfig);
    assertThat(runConfig.inputAudioTranscription()).isEqualTo(audioTranscriptionConfig);
    assertThat(runConfig.maxLlmCalls()).isEqualTo(10);
  }

  @Test
  public void testBuilderDefaults() {
    RunConfig runConfig = RunConfig.builder().build();

    assertThat(runConfig.speechConfig()).isNull();
    assertThat(runConfig.responseModalities()).isEmpty();
    assertThat(runConfig.saveInputBlobsAsArtifacts()).isFalse();
    assertThat(runConfig.streamingMode()).isEqualTo(RunConfig.StreamingMode.NONE);
    assertThat(runConfig.outputAudioTranscription()).isNull();
    assertThat(runConfig.inputAudioTranscription()).isNull();
    assertThat(runConfig.maxLlmCalls()).isEqualTo(500);
  }

  @Test
  public void testMaxLlmCalls_negativeValueAllowedInSetterButLoggedAndBuilt() {
    RunConfig runConfig = RunConfig.builder().setMaxLlmCalls(-1).build();
    assertThat(runConfig.maxLlmCalls()).isEqualTo(-1);
  }

  @Test
  public void testBuilderWithDifferentValues() {
    SpeechConfig speechConfig = SpeechConfig.builder().build();
    AudioTranscriptionConfig audioTranscriptionConfig = AudioTranscriptionConfig.builder().build();

    RunConfig runConfig =
        RunConfig.builder()
            .setSpeechConfig(speechConfig)
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setSaveInputBlobsAsArtifacts(true)
            .setStreamingMode(RunConfig.StreamingMode.BIDI)
            .setOutputAudioTranscription(audioTranscriptionConfig)
            .setInputAudioTranscription(audioTranscriptionConfig)
            .setMaxLlmCalls(20)
            .build();

    assertThat(runConfig.speechConfig()).isEqualTo(speechConfig);
    assertThat(runConfig.responseModalities()).containsExactly(new Modality(Modality.Known.AUDIO));
    assertThat(runConfig.saveInputBlobsAsArtifacts()).isTrue();
    assertThat(runConfig.streamingMode()).isEqualTo(RunConfig.StreamingMode.BIDI);
    assertThat(runConfig.outputAudioTranscription()).isEqualTo(audioTranscriptionConfig);
    assertThat(runConfig.inputAudioTranscription()).isEqualTo(audioTranscriptionConfig);
    assertThat(runConfig.maxLlmCalls()).isEqualTo(20);
  }

  @Test
  public void testInputAudioTranscriptionOnly() {
    AudioTranscriptionConfig inputTranscriptionConfig = AudioTranscriptionConfig.builder().build();

    RunConfig runConfig =
        RunConfig.builder()
            .setStreamingMode(RunConfig.StreamingMode.BIDI)
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setInputAudioTranscription(inputTranscriptionConfig)
            .build();

    assertThat(runConfig.inputAudioTranscription()).isEqualTo(inputTranscriptionConfig);
    assertThat(runConfig.outputAudioTranscription()).isNull();
    assertThat(runConfig.streamingMode()).isEqualTo(RunConfig.StreamingMode.BIDI);
    assertThat(runConfig.responseModalities()).containsExactly(new Modality(Modality.Known.AUDIO));
  }

  @Test
  public void testMaxLlmCalls_integerMaxValue_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RunConfig.builder().setMaxLlmCalls(Integer.MAX_VALUE).build());
  }
}
