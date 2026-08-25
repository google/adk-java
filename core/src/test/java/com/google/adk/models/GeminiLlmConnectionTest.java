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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerSetupComplete;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.LiveServerToolCallCancellation;
import com.google.genai.types.Part;
import com.google.genai.types.UsageMetadata;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GeminiLlmConnectionTest {

  @Test
  public void convertToServerResponse_withInterruptedTrue_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Model response")))
            .turnComplete(false)
            .interrupted(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();
    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);

    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("Model response");
    assertThat(response.partial()).hasValue(true);
    assertThat(response.turnComplete()).hasValue(false);
    assertThat(response.interrupted()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withInterruptedFalse_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Continuing response")))
            .turnComplete(false)
            .interrupted(false)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.interrupted()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withoutInterruptedField_mapsEmptyOptional() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Normal response")))
            .turnComplete(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.interrupted()).isEmpty();
    assertThat(response.turnComplete()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withTurnCompleteTrue_mapsPartialFalse() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Final response")))
            .turnComplete(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withTurnCompleteFalse_mapsPartialTrue() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Partial response")))
            .turnComplete(false)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.partial()).hasValue(true);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withToolCall_mapsContentWithFunctionCall() {
    FunctionCall functionCall = FunctionCall.builder().name("tool").build();
    LiveServerToolCall toolCall =
        LiveServerToolCall.builder().functionCalls(ImmutableList.of(functionCall)).build();

    LiveServerMessage message = LiveServerMessage.builder().toolCall(toolCall).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(1);
    assertThat(response.content().get().parts().get().get(0).functionCall()).hasValue(functionCall);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withMultipleFunctionCalls_preservesAllCallsAsParts() {
    // Regression test for parallel tool calling on the live/BIDI path: a single toolCall message
    // can carry multiple FunctionCalls, and all of them must be preserved (not just the last).
    FunctionCall getWeather =
        FunctionCall.builder().name("getWeather").args(ImmutableMap.of("city", "Paris")).build();
    FunctionCall getTime =
        FunctionCall.builder()
            .name("getTime")
            .args(ImmutableMap.of("timezone", "Europe/London"))
            .build();
    LiveServerToolCall toolCall =
        LiveServerToolCall.builder().functionCalls(ImmutableList.of(getWeather, getTime)).build();

    LiveServerMessage message = LiveServerMessage.builder().toolCall(toolCall).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(2);
    assertThat(response.content().get().parts().get().get(0).functionCall()).hasValue(getWeather);
    assertThat(response.content().get().parts().get().get(1).functionCall()).hasValue(getTime);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withUsageMetadata_mapsGenerateResponseUsageMetadata() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .usageMetadata(
                UsageMetadata.builder()
                    .promptTokenCount(10)
                    .responseTokenCount(20)
                    .totalTokenCount(30)
                    .build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);
    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.usageMetadata()).isPresent();
    GenerateContentResponseUsageMetadata expectedUsageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    assertThat(response.usageMetadata()).hasValue(expectedUsageMetadata);
  }

  @Test
  public void convertToServerResponse_withToolCallCancellation_returnsNoValues() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .toolCallCancellation(LiveServerToolCallCancellation.builder().build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);
    testObserver.assertNoValues();
    testObserver.assertComplete();
  }

  @Test
  public void convertToServerResponse_withSetupComplete_returnsNoValues() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .setupComplete(LiveServerSetupComplete.builder().build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertNoValues();
    testObserver.assertComplete();
  }

  @Test
  public void convertToServerResponse_withUnknownMessage_returnsErrorResponse() {
    LiveServerMessage message = LiveServerMessage.builder().build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.errorCode()).isPresent();
    assertThat(response.errorMessage()).hasValue("Received unknown server message.");
  }

  @Test
  public void convertToServerResponse_withContentAndUsageMetadata_emitsMultiple() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Model response")))
            .turnComplete(true)
            .build();

    UsageMetadata usageMetadata =
        UsageMetadata.builder()
            .promptTokenCount(10)
            .responseTokenCount(20)
            .totalTokenCount(30)
            .build();

    LiveServerMessage message =
        LiveServerMessage.builder()
            .serverContent(serverContent)
            .usageMetadata(usageMetadata)
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message).subscribe(testObserver);

    testObserver.assertValueCount(2);
    testObserver.assertComplete();

    List<LlmResponse> responses = testObserver.values();

    // Check for ServerContent response
    LlmResponse contentResponse = responses.get(0);
    assertThat(contentResponse.content()).isPresent();
    assertThat(contentResponse.content().get().text()).isEqualTo("Model response");
    assertThat(contentResponse.usageMetadata()).isEmpty();

    // Check for UsageMetadata response
    LlmResponse usageResponse = responses.get(1);
    assertThat(usageResponse.content()).isEmpty();
    assertThat(usageResponse.usageMetadata()).isPresent();
    GenerateContentResponseUsageMetadata expectedUsageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    assertThat(usageResponse.usageMetadata()).hasValue(expectedUsageMetadata);
  }

  @Test
  public void receive_completesWhenTransportSignalsStreamEnd() {
    // A transport that ends its own receive stream completes the connection's response stream, so a
    // live run with no client close still terminates.
    FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ true);
    GeminiLlmConnection connection =
        new GeminiLlmConnection(CompletableFuture.completedFuture(transport));

    TestSubscriber<LlmResponse> subscriber = connection.receive().test();
    subscriber.awaitDone(5, TimeUnit.SECONDS);

    subscriber.assertComplete();
    subscriber.assertNoErrors();
  }

  @Test
  public void close_afterTransportStreamEnd_isNoOp() {
    FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ true);
    GeminiLlmConnection connection =
        new GeminiLlmConnection(CompletableFuture.completedFuture(transport));
    TestSubscriber<LlmResponse> subscriber = connection.receive().test();
    subscriber.awaitDone(5, TimeUnit.SECONDS);
    subscriber.assertComplete();

    connection.close(); // Already completed via stream-end; must not re-terminate or error.

    subscriber.assertComplete();
    subscriber.assertNoErrors();
    // completeReceive leaves the transport to close itself, so no close() is issued on this path.
    assertThat(transport.closeCount.get()).isEqualTo(0);
  }

  /**
   * A minimal in-process {@link GeminiLiveTransport} that records closes and can end its stream.
   */
  private static final class FakeTransport implements GeminiLiveTransport {
    private final boolean endStreamOnReceive;
    final AtomicInteger closeCount = new AtomicInteger();

    FakeTransport(boolean endStreamOnReceive) {
      this.endStreamOnReceive = endStreamOnReceive;
    }

    @Override
    public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> receive(
        Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd) {
      if (endStreamOnReceive) {
        onStreamEnd.run();
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
      closeCount.incrementAndGet();
      return CompletableFuture.completedFuture(null);
    }
  }
}
