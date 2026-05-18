package com.google.adk.base.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultOpenAiMessageSerializerTest {

  private DefaultOpenAiMessageSerializer serializer;

  @BeforeEach
  void setUp() {
    serializer = new DefaultOpenAiMessageSerializer();
  }

  // Helper: subscribes synchronously, collecting all emitted responses.
  // processStreamLines() calls onNext but never onComplete (it is the caller's responsibility),
  // so we collect values directly from the Flowable using blockingIterable.
  private List<LlmResponse> collect(List<String> lines) throws Exception {
    List<LlmResponse> results = new ArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    Flowable.<LlmResponse>create(
            emitter -> {
              try {
                serializer.processStreamLines(lines.stream(), emitter);
              } catch (Exception e) {
                emitter.onError(e);
                return;
              }
              emitter.onComplete();
            },
            BackpressureStrategy.BUFFER)
        .blockingForEach(results::add);

    return results;
  }

  // -----------------------------------------------------------------------
  // processStreamLines - text streaming
  // -----------------------------------------------------------------------

  @Test
  void processStreamLines_textChunks_emitsPartialsThenFinal() throws Exception {
    List<String> lines =
        List.of(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\", world\"}}]}",
            "data: [DONE]");

    List<LlmResponse> responses = collect(lines);

    // Two partial tokens + one final assembled response
    assertThat(responses).hasSize(3);

    // First two are partial
    assertThat(responses.get(0).partial()).hasValue(true);
    assertThat(responses.get(1).partial()).hasValue(true);

    // Final response has the full concatenated text and turnComplete=true
    LlmResponse finalResponse = responses.get(2);
    assertThat(finalResponse.partial()).hasValue(false);
    assertThat(finalResponse.turnComplete()).hasValue(true);
    String fullText = finalResponse.content().get().parts().get().get(0).text().orElse("");
    assertThat(fullText).isEqualTo("Hello, world");
  }

  @Test
  void processStreamLines_emptyStream_emitsFinalEmptyResponse() throws Exception {
    List<String> lines = List.of("data: [DONE]");

    List<LlmResponse> responses = collect(lines);

    assertThat(responses).hasSize(1);
    LlmResponse finalResponse = responses.get(0);
    assertThat(finalResponse.turnComplete()).hasValue(true);
  }

  @Test
  void processStreamLines_nonDataLinesIgnored() throws Exception {
    List<String> lines =
        List.of(
            ": keep-alive",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}",
            "data: [DONE]");

    List<LlmResponse> responses = collect(lines);

    // One partial + one final
    assertThat(responses).hasSize(2);
    assertThat(responses.get(0).partial()).hasValue(true);
  }

  // -----------------------------------------------------------------------
  // processStreamLines - tool call streaming (the bug we fixed)
  // -----------------------------------------------------------------------

  @Test
  void processStreamLines_toolCall_assemblesArgumentsAcrossChunks() throws Exception {
    // Arguments arrive fragmented across multiple chunks, as they do in real SSE streams
    List<String> lines =
        List.of(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"\\\"London\\\"}\"}}]}}]}",
            "data: [DONE]");

    List<LlmResponse> responses = collect(lines);

    // Single tool call response emitted at end
    assertThat(responses).hasSize(1);
    LlmResponse toolResponse = responses.get(0);
    assertThat(toolResponse.turnComplete()).hasValue(false);

    var parts = toolResponse.content().get().parts().get();
    assertThat(parts).hasSize(1);
    var functionCall = parts.get(0).functionCall();
    assertThat(functionCall).isPresent();
    assertThat(functionCall.get().name()).hasValue("get_weather");
    assertThat(functionCall.get().args().get()).containsKey("city");
    assertThat(functionCall.get().args().get().get("city")).isEqualTo("London");
  }

  @Test
  void processStreamLines_multipleToolCalls_assemblesBothByIndex() throws Exception {
    List<String> lines =
        List.of(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"tool_a\","
                + "\"arguments\":\"{\\\"x\\\":1}\"}},"
                + "{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"tool_b\","
                + "\"arguments\":\"{\\\"y\\\":2}\"}}"
                + "]}}]}",
            "data: [DONE]");

    List<LlmResponse> responses = collect(lines);

    var parts = responses.get(0).content().get().parts().get();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("tool_a");
    assertThat(parts.get(1).functionCall().get().name()).hasValue("tool_b");
  }

  // -----------------------------------------------------------------------
  // processStreamLines - error handling
  // -----------------------------------------------------------------------

  @Test
  void processStreamLines_errorChunk_throwsIllegalArgumentException() {
    List<String> lines =
        List.of("data: {\"error\":{\"message\":\"Invalid API key\",\"type\":\"auth_error\"}}");

    assertThatThrownBy(() -> collect(lines))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid API key");
  }

  @Test
  void processStreamLines_malformedJsonChunk_skippedGracefully() throws Exception {
    List<String> lines =
        List.of(
            "data: {not valid json}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}",
            "data: [DONE]");

    List<LlmResponse> responses = collect(lines);

    // Malformed line is skipped; valid chunk still processed
    assertThat(responses).isNotEmpty();
    // One partial ("OK") + one final
    assertThat(responses).hasSize(2);
  }

  // -----------------------------------------------------------------------
  // deserializeResponse - non-streaming
  // -----------------------------------------------------------------------

  @Test
  void deserializeResponse_textContent_returnsLlmResponseWithText() throws Exception {
    String body =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "content": "The capital of France is Paris."
            },
            "finish_reason": "stop"
          }]
        }
        """;

    LlmResponse response = serializer.deserializeResponse(body);

    assertThat(response).isNotNull();
    String text = response.content().get().parts().get().get(0).text().orElse("");
    assertThat(text).isEqualTo("The capital of France is Paris.");
  }

  @Test
  void deserializeResponse_toolCallContent_returnsFunctionCallPart() throws Exception {
    String body =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "tool_calls": [{
                "id": "call_abc",
                "function": {
                  "name": "get_weather",
                  "arguments": "{\\"city\\":\\"Paris\\"}"
                }
              }]
            },
            "finish_reason": "tool_calls"
          }]
        }
        """;

    LlmResponse response = serializer.deserializeResponse(body);

    assertThat(response).isNotNull();
    var parts = response.content().get().parts().get();
    assertThat(parts).hasSize(1);
    var functionCall = parts.get(0).functionCall();
    assertThat(functionCall).isPresent();
    assertThat(functionCall.get().name()).hasValue("get_weather");
    assertThat(functionCall.get().args().get()).containsEntry("city", "Paris");
  }
}
