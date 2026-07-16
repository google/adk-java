package com.google.adk.models.chat;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.chat.ChatCompletionsResponse.ChatCompletion;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.MediaModality;
import com.google.genai.types.ModalityTokenCount;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ChatCompletionsResponseAudioTokensTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void testDeserializeChatCompletion_withAudioTokens() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1677652288,
          "model": "gpt-4o-audio-preview",
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "Hello!"
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": 9,
            "completion_tokens": 12,
            "total_tokens": 21,
            "prompt_tokens_details": {
              "audio_tokens": 5
            },
            "completion_tokens_details": {
              "audio_tokens": 7
            }
          }
        }
        """;

    ChatCompletion completion = objectMapper.readValue(json, ChatCompletion.class);
    LlmResponse response = completion.toLlmResponse();

    GenerateContentResponseUsageMetadata usage = response.usageMetadata().get();

    List<ModalityTokenCount> promptDetails = usage.promptTokensDetails().get();
    assertThat(promptDetails).hasSize(1);
    assertThat(promptDetails.get(0).modality().get())
        .isEqualTo(new MediaModality(MediaModality.Known.AUDIO));
    assertThat(promptDetails.get(0).tokenCount().get()).isEqualTo(5);

    List<ModalityTokenCount> completionDetails = usage.candidatesTokensDetails().get();
    assertThat(completionDetails).hasSize(1);
    assertThat(completionDetails.get(0).modality().get())
        .isEqualTo(new MediaModality(MediaModality.Known.AUDIO));
    assertThat(completionDetails.get(0).tokenCount().get()).isEqualTo(7);
  }
}
