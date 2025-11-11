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
package com.google.adk.models.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamingResponseAggregatorTest {

  private StreamingResponseAggregator aggregator;

  @BeforeEach
  void setUp() {
    aggregator = new StreamingResponseAggregator();
  }

  @Test
  void testIsEmptyInitially() {
    assertThat(aggregator.isEmpty()).isTrue();
    assertThat(aggregator.getAccumulatedTextLength()).isEqualTo(0);
  }

  @Test
  void testProcessStreamingResponseWithEmptyContent() {
    LlmResponse emptyResponse = LlmResponse.builder().build();

    LlmResponse result = aggregator.processStreamingResponse(emptyResponse);

    assertThat(result).isEqualTo(emptyResponse);
    assertThat(aggregator.isEmpty()).isTrue();
  }

  @Test
  void testProcessStreamingResponseWithEmptyParts() {
    Content emptyContent = Content.builder().role("model").build();

    LlmResponse response = LlmResponse.builder().content(emptyContent).build();

    LlmResponse result = aggregator.processStreamingResponse(response);

    assertThat(result).isEqualTo(response);
    assertThat(aggregator.isEmpty()).isTrue();
  }

  @Test
  void testProcessSingleTextResponse() {
    Content textContent =
        Content.builder().role("model").parts(List.of(Part.fromText("Hello"))).build();

    LlmResponse response =
        LlmResponse.builder().content(textContent).partial(true).turnComplete(false).build();

    LlmResponse result = aggregator.processStreamingResponse(response);

    assertThat(result.content()).isPresent();
    assertThat(result.content().get().parts()).isPresent();
    assertThat(result.content().get().parts().get()).hasSize(1);
    assertThat(result.content().get().parts().get().get(0).text()).contains("Hello");
    assertThat(result.partial()).contains(true);
    assertThat(result.turnComplete()).contains(false);

    assertThat(aggregator.isEmpty()).isFalse();
    assertThat(aggregator.getAccumulatedTextLength()).isEqualTo(5);
  }

  @Test
  void testProcessMultipleTextResponses() {
    Content firstContent =
        Content.builder().role("model").parts(List.of(Part.fromText("Hello"))).build();

    Content secondContent =
        Content.builder().role("model").parts(List.of(Part.fromText(" world"))).build();

    Content thirdContent =
        Content.builder().role("model").parts(List.of(Part.fromText("!"))).build();

    LlmResponse first = LlmResponse.builder().content(firstContent).partial(true).build();

    LlmResponse second = LlmResponse.builder().content(secondContent).partial(true).build();

    LlmResponse third = LlmResponse.builder().content(thirdContent).partial(false).build();

    LlmResponse result1 = aggregator.processStreamingResponse(first);
    LlmResponse result2 = aggregator.processStreamingResponse(second);
    LlmResponse result3 = aggregator.processStreamingResponse(third);

    assertThat(result3.content()).isPresent();
    assertThat(result3.content().get().parts()).isPresent();
    assertThat(result3.content().get().parts().get()).hasSize(1);
    assertThat(result3.content().get().parts().get().get(0).text()).contains("Hello world!");

    assertThat(aggregator.getAccumulatedTextLength()).isEqualTo(12);
  }

  @Test
  void testProcessFunctionCallResponse() {
    FunctionCall functionCall =
        FunctionCall.builder()
            .name("get_weather")
            .args(Map.of("location", "San Francisco"))
            .id("call_123")
            .build();

    Content functionContent =
        Content.builder()
            .role("model")
            .parts(
                List.of(
                    Part.fromFunctionCall(
                        functionCall.name().orElse(""), functionCall.args().orElse(Map.of()))))
            .build();

    LlmResponse response = LlmResponse.builder().content(functionContent).build();

    LlmResponse result = aggregator.processStreamingResponse(response);

    assertThat(result.content()).isPresent();
    assertThat(result.content().get().parts()).isPresent();
    assertThat(result.content().get().parts().get()).hasSize(1);
    assertThat(result.content().get().parts().get().get(0).functionCall()).isPresent();
    assertThat(result.content().get().parts().get().get(0).functionCall().get().name())
        .contains("get_weather");
  }

  @Test
  void testProcessMixedTextAndFunctionCallResponses() {
    Content textContent =
        Content.builder()
            .role("model")
            .parts(List.of(Part.fromText("Let me check the weather for you.")))
            .build();

    FunctionCall functionCall =
        FunctionCall.builder()
            .name("get_weather")
            .args(Map.of("location", "San Francisco"))
            .build();

    Content functionContent =
        Content.builder()
            .role("model")
            .parts(
                List.of(
                    Part.fromFunctionCall(
                        functionCall.name().orElse(""), functionCall.args().orElse(Map.of()))))
            .build();

    LlmResponse textResponse = LlmResponse.builder().content(textContent).partial(true).build();

    LlmResponse functionResponse =
        LlmResponse.builder().content(functionContent).partial(false).turnComplete(true).build();

    LlmResponse result1 = aggregator.processStreamingResponse(textResponse);
    LlmResponse result2 = aggregator.processStreamingResponse(functionResponse);

    assertThat(result2.content()).isPresent();
    assertThat(result2.content().get().parts()).isPresent();
    assertThat(result2.content().get().parts().get()).hasSize(2);

    Part textPart = result2.content().get().parts().get().get(0);
    Part functionPart = result2.content().get().parts().get().get(1);

    assertThat(textPart.text()).contains("Let me check the weather for you.");
    assertThat(functionPart.functionCall()).isPresent();
    assertThat(functionPart.functionCall().get().name()).contains("get_weather");
  }

  @Test
  void testGetFinalResponse() {
    Content content1 =
        Content.builder().role("model").parts(List.of(Part.fromText("Hello"))).build();

    Content content2 =
        Content.builder().role("model").parts(List.of(Part.fromText(" world"))).build();

    LlmResponse response1 = LlmResponse.builder().content(content1).partial(true).build();

    LlmResponse response2 = LlmResponse.builder().content(content2).partial(true).build();

    aggregator.processStreamingResponse(response1);
    aggregator.processStreamingResponse(response2);

    LlmResponse finalResponse = aggregator.getFinalResponse();

    assertThat(finalResponse.content()).isPresent();
    assertThat(finalResponse.content().get().parts()).isPresent();
    assertThat(finalResponse.content().get().parts().get()).hasSize(1);
    assertThat(finalResponse.content().get().parts().get().get(0).text()).contains("Hello world");
    assertThat(finalResponse.partial()).contains(false);
    assertThat(finalResponse.turnComplete()).contains(true);

    // Aggregator should be reset after getFinalResponse
    assertThat(aggregator.isEmpty()).isTrue();
    assertThat(aggregator.getAccumulatedTextLength()).isEqualTo(0);
  }

  @Test
  void testReset() {
    Content content =
        Content.builder().role("model").parts(List.of(Part.fromText("Some text"))).build();

    LlmResponse response = LlmResponse.builder().content(content).build();

    aggregator.processStreamingResponse(response);

    assertThat(aggregator.isEmpty()).isFalse();
    assertThat(aggregator.getAccumulatedTextLength()).isGreaterThan(0);

    aggregator.reset();

    assertThat(aggregator.isEmpty()).isTrue();
    assertThat(aggregator.getAccumulatedTextLength()).isEqualTo(0);
  }

  @Test
  void testMultiplePartsInSingleResponse() {
    Content multiPartContent =
        Content.builder()
            .role("model")
            .parts(List.of(Part.fromText("First part. "), Part.fromText("Second part.")))
            .build();

    LlmResponse response = LlmResponse.builder().content(multiPartContent).build();

    LlmResponse result = aggregator.processStreamingResponse(response);

    assertThat(result.content()).isPresent();
    assertThat(result.content().get().parts()).isPresent();
    assertThat(result.content().get().parts().get()).hasSize(1);
    assertThat(result.content().get().parts().get().get(0).text())
        .contains("First part. Second part.");

    assertThat(aggregator.getAccumulatedTextLength())
        .isEqualTo(24); // "First part. " (12) + "Second part." (12) = 24
  }

  @Test
  void testPartialAndTurnCompleteFlags() {
    Content content = Content.builder().role("model").parts(List.of(Part.fromText("Test"))).build();

    LlmResponse partialResponse =
        LlmResponse.builder().content(content).partial(true).turnComplete(false).build();

    LlmResponse completeResponse =
        LlmResponse.builder().content(content).partial(false).turnComplete(true).build();

    LlmResponse result1 = aggregator.processStreamingResponse(partialResponse);
    assertThat(result1.partial()).contains(true);
    assertThat(result1.turnComplete()).contains(false);

    aggregator.reset();

    LlmResponse result2 = aggregator.processStreamingResponse(completeResponse);
    assertThat(result2.partial()).contains(false);
    assertThat(result2.turnComplete()).contains(true);
  }

  @Test
  void testGetFinalResponseWithNoProcessedResponses() {
    LlmResponse finalResponse = aggregator.getFinalResponse();

    assertThat(finalResponse.content()).isPresent();
    assertThat(finalResponse.content().get().parts()).isPresent();
    assertThat(finalResponse.content().get().parts().get()).isEmpty();
    assertThat(finalResponse.partial()).contains(false);
    assertThat(finalResponse.turnComplete()).contains(true);
  }
}
