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
package com.google.adk.plugins.recordings;

import static com.google.common.truth.Truth.assertThat;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

class RecordingsLoaderTest {

  private static Part getOnlyPart(Content content) {
    var parts = content.parts().orElseThrow();
    if (parts.size() != 1) {
      throw new IllegalStateException("Expected exactly one part, but found " + parts.size());
    }
    return parts.get(0);
  }

  @Test
  void testLoadCRecording() throws Exception {
    // This test demonstrates all the key deserialization features:
    // 1. snake_case to camelCase field name conversion (e.g., user_message_index ->
    // userMessageIndex)
    // 2. ContentUnion deserialization (system_instruction as a string)
    // 3. URL-safe Base64 decoding for byte[] fields (thought_signature with '_' and '-')
    // 4. FunctionResponse deserialization with camelCase @JsonProperty annotations
    String yamlContent =
        """
        recordings:
          - user_message_index: 0
            agent_name: booking_assistant
            llm_recording:
              llm_request:
                model: gemini-2.5-flash
                contents:
                  - parts:
                      - text: "I want to create a booking for test@example.com."
                    role: user
                config:
                  system_instruction: |-
                    You are a booking assistant. You can validate emails and create bookings.
                  tools:
                    - function_declarations:
                        - name: validate_email
                          description: Validates email format
              llm_response:
                content:
                  parts:
                    - thought_signature: Cq0EAR_MhbYyfIgI1M5KlVyG9HzjQ_CvZiHb_RQ2KR0H_UkDj-LDdxdVayqSpG8F6wPq4aGB6lZlqjZIGvA5H2zX2RQ_Iu8Wb8t_wKoEpW4XcwzzU9Org_ZvTNx4TZHll5cH5ebo1LPRWfTqVn7cC1N5KwDZtS2XLwCmitucAAKGzGH4c-tM0dgj57NoMFa63iaHizzi2zupKoGPBB-ZmakNHAHRspkl85hKaq8m4fELHNNMnyi596jcGRHxTDBiqHmNG8PyRiOXRM9VOkNnPU8l2DN7b6CvaBPmH84t0MaHxFMmrMjTQaNTBw92lXT7LZfwYJrDxf1ZpVHjztpbIhfZyYyZmxhIDNcVlb5i4Xoe8Rcva51NgBJN-UAm9cXWBSvr2_EdQbWs7Tz57niquyLpD6fhnTPOWBN6PU2Nz5nMgq-SUyM7srg2Ta6OV9uwOYFAFl0klSBouZ44YTM-T-voCin7EobkTzzXcllDPJ5TPretD_mpkeATlJ3Gi3nPfFLuU2DqFb8fLZjovY5oseSkEvf6NYnGt26r290QzG0cFsZbpJdtysBL-lH-yOwKEl-26IjiWztk0wAxnIdrmILlD9hgXRuyudXI0hx4gH1KTIH7njNNyLMNevUYVGC4cGxa1IpCh4EevhfCT9PQYM-QPyRT4dRBNzoG_y_lZERctUNHAfp80ObBClHEvDjElC2H6kWlO_jBeDiyJpezO7OeYjmDipvKFk3rQgNP87A=
                      function_call:
                        name: validate_email
                        args:
                          email: test@example.com
                  role: model
                finish_reason: STOP
          - user_message_index: 0
            agent_name: booking_assistant
            tool_recording:
              tool_call:
                id: adk-test-123
                name: validate_email
                args:
                  email: test@example.com
              tool_response:
                id: adk-test-123
                name: validate_email
                response:
                  result: true
        """;

    Recordings recordings = RecordingsLoader.load(yamlContent);

    // Verify basic structure
    assertThat(recordings).isNotNull();
    assertThat(recordings.recordings()).isNotNull();
    assertThat(recordings.recordings()).hasSize(2);

    // Verify first recording (LLM recording with all complex features)
    Recording firstRecording = recordings.recordings().get(0);
    assertThat(firstRecording.userMessageIndex()).isEqualTo(0);
    assertThat(firstRecording.agentName()).isEqualTo("booking_assistant");
    assertThat(firstRecording.llmRecording()).isPresent();

    // Verify snake_case to camelCase conversion for nested fields
    var llmRequest = firstRecording.llmRecording().get().llmRequest();
    assertThat(llmRequest).isPresent();
    assertThat(llmRequest.get().model()).hasValue("gemini-2.5-flash");

    // Verify Content string deserialization (system_instruction as string -> Content object)
    var systemInstructionContent = llmRequest.get().config().get().systemInstruction().get();
    var systemInstructionText = getOnlyPart(systemInstructionContent).text();
    assertThat(systemInstructionText).isPresent();
    assertThat(systemInstructionText.get()).contains("booking assistant");

    // Verify URL-safe Base64 deserialization (thought_signature with '_' and '-' characters)
    var responseContent = firstRecording.llmRecording().get().llmResponse().get().content().get();
    var thoughtSignature = getOnlyPart(responseContent).thoughtSignature();
    assertThat(thoughtSignature).isPresent();
    assertThat(thoughtSignature.get()).isNotEmpty();

    // Verify FunctionCall deserialization (camelCase @JsonProperty from dependency)
    var functionCallName = getOnlyPart(responseContent).functionCall().get().name();
    assertThat(functionCallName).hasValue("validate_email");

    // Verify second recording (Tool recording with FunctionResponse)
    Recording secondRecording = recordings.recordings().get(1);
    assertThat(secondRecording.toolRecording()).isPresent();

    // Verify FunctionResponse deserialization (camelCase @JsonProperty from dependency)
    var toolResponseName = secondRecording.toolRecording().get().toolResponse().get().name();
    var toolResponseId = secondRecording.toolRecording().get().toolResponse().get().id();
    assertThat(toolResponseName).hasValue("validate_email");
    assertThat(toolResponseId).hasValue("adk-test-123");
  }
}
