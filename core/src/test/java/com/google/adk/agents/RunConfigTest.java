package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;

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
}
