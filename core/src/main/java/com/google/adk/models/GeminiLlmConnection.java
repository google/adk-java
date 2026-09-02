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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.Part;
import com.google.genai.types.UsageMetadata;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.net.SocketException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a persistent, bidirectional connection to the Gemini model via WebSockets for real-time
 * interaction.
 *
 * <p>This connection allows sending conversation history, individual messages, function responses,
 * and real-time media blobs (like audio chunks) while continuously receiving responses from the
 * model.
 */
public final class GeminiLlmConnection implements BaseLlmConnection {

  private static final Logger logger = LoggerFactory.getLogger(GeminiLlmConnection.class);

  private final CompletableFuture<GeminiLiveTransport> transportFuture;
  private final PublishProcessor<LlmResponse> responseProcessor = PublishProcessor.create();
  private final Flowable<LlmResponse> responseFlowable = responseProcessor.serialize();
  private final CompositeDisposable disposables = new CompositeDisposable();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Establishes a new connection over the given live transport.
   *
   * @param transportFuture The live transport the connection drives, once established.
   */
  GeminiLlmConnection(CompletableFuture<GeminiLiveTransport> transportFuture) {
    this.transportFuture =
        Objects.requireNonNull(transportFuture)
            .whenCompleteAsync(
                (transport, throwable) -> {
                  if (throwable != null) {
                    handleConnectionError(throwable);
                  } else if (transport != null) {
                    setupReceiver(transport);
                  } else if (!closed.get()) {
                    handleConnectionError(
                        new SocketException("WebSocket connection failed without explicit error."));
                  }
                });
  }

  /** Configures the transport to forward incoming messages to the response processor. */
  private void setupReceiver(GeminiLiveTransport transport) {
    if (closed.get()) {
      closeTransportIgnoringErrors(transport);
      return;
    }
    transport
        .receive(this::handleServerMessage, this::completeReceive)
        .exceptionally(
            error -> {
              handleReceiveError(error);
              return null;
            });
  }

  /** Completes the response stream when the transport's receive stream ends, ending the run. */
  private void completeReceive() {
    // Only a transport that ends its own stream reaches this, so it owns its close; none is issued.
    if (closed.compareAndSet(false, true)) {
      responseProcessor.onComplete();
      disposables.dispose();
    }
  }

  /** Processes messages received from the WebSocket server. */
  private void handleServerMessage(LiveServerMessage message) {
    if (closed.get()) {
      return;
    }

    logger.debug("Received server message: {}", message.toJson());

    Observable<LlmResponse> llmResponse = convertToServerResponse(message);
    if (!disposables.add(
        llmResponse.subscribe(responseProcessor::onNext, responseProcessor::onError))) {
      logger.warn(
          "disposables container already disposed, the subscription will be disposed immediately");
    }
  }

  /** Converts a server message into the standardized LlmResponse format. */
  static Observable<LlmResponse> convertToServerResponse(LiveServerMessage message) {
    return Observable.create(
        emitter -> {
          // AtomicBoolean is used to modify state from within lambdas, which
          // require captured variables to be effectively final.
          final AtomicBoolean handled = new AtomicBoolean(false);
          message
              .serverContent()
              .ifPresent(
                  serverContent -> {
                    emitter.onNext(createServerContentResponse(serverContent));
                    handled.set(true);
                  });
          message
              .toolCall()
              .ifPresent(
                  toolCall -> {
                    emitter.onNext(createToolCallResponse(toolCall));
                    handled.set(true);
                  });
          message
              .usageMetadata()
              .ifPresent(
                  usageMetadata -> {
                    logger.debug("Received usage metadata: {}", usageMetadata);
                    emitter.onNext(createUsageMetadataResponse(usageMetadata));
                    handled.set(true);
                  });
          message
              .toolCallCancellation()
              .ifPresent(
                  toolCallCancellation -> {
                    logger.debug("Received tool call cancellation: {}", toolCallCancellation);
                    // TODO: implement proper CFC and thus tool call cancellation handling.
                    handled.set(true);
                  });
          message
              .setupComplete()
              .ifPresent(
                  setupComplete -> {
                    logger.debug("Received setup complete.");
                    handled.set(true);
                  });

          if (!handled.get()) {
            logger.warn("Received unknown or empty server message: {}", message.toJson());
            emitter.onNext(createUnknownMessageResponse());
          }
          emitter.onComplete();
        });
  }

  private static LlmResponse createServerContentResponse(LiveServerContent serverContent) {
    LlmResponse.Builder builder = LlmResponse.builder();
    serverContent.modelTurn().ifPresent(builder::content);
    return builder
        .partial(serverContent.turnComplete().map(completed -> !completed).orElse(false))
        .turnComplete(serverContent.turnComplete().orElse(false))
        .interrupted(serverContent.interrupted().orElse(null))
        .inputTranscription(serverContent.inputTranscription().orElse(null))
        .outputTranscription(serverContent.outputTranscription().orElse(null))
        .build();
  }

  private static LlmResponse createToolCallResponse(LiveServerToolCall toolCall) {
    LlmResponse.Builder builder = LlmResponse.builder();
    toolCall
        .functionCalls()
        .ifPresent(
            calls ->
                builder.content(
                    Content.builder()
                        .role("model")
                        .parts(
                            calls.stream()
                                .map(call -> Part.builder().functionCall(call).build())
                                .collect(toImmutableList()))
                        .build()));
    return builder.partial(false).turnComplete(false).build();
  }

  private static LlmResponse createUsageMetadataResponse(UsageMetadata usageMetadata) {
    return LlmResponse.builder()
        .usageMetadata(GeminiUtil.toGenerateContentResponseUsageMetadata(usageMetadata))
        .build();
  }

  private static LlmResponse createUnknownMessageResponse() {
    return LlmResponse.builder()
        .errorCode(new FinishReason("Unknown server message."))
        .errorMessage("Received unknown server message.")
        .build();
  }

  /** Handles errors that occur *during* the initial connection attempt. */
  private void handleConnectionError(Throwable throwable) {
    if (closed.compareAndSet(false, true)) {
      logger.error("WebSocket connection failed", throwable);
      Throwable cause =
          (throwable instanceof CompletionException) ? throwable.getCause() : throwable;
      responseProcessor.onError(cause);
    }
  }

  /** Handles errors reported by the WebSocket client *after* connection (e.g., receive errors). */
  private void handleReceiveError(Throwable throwable) {
    if (closed.compareAndSet(false, true)) {
      logger.error("Error during WebSocket receive operation", throwable);
      responseProcessor.onError(throwable);
      transportFuture
          .thenAccept(this::closeTransportIgnoringErrors)
          .exceptionally(unusedError -> null);
    }
  }

  @Override
  public Completable sendHistory(List<Content> history) {
    return sendClientContentInternal(
        LiveSendClientContentParameters.builder().turns(history).build());
  }

  @Override
  public Completable sendContent(Content content) {
    return sendContent(content, true);
  }

  @Override
  public Completable sendContent(Content content, boolean turnComplete) {
    Objects.requireNonNull(content, "content cannot be null");

    List<FunctionResponse> functionResponses = extractFunctionResponses(content);
    if (functionResponses.isEmpty()) {
      return sendClientContentInternal(
          LiveSendClientContentParameters.builder()
              .turns(ImmutableList.of(content))
              .turnComplete(turnComplete)
              .build());
    }
    return sendToolResponseInternal(
        LiveSendToolResponseParameters.builder().functionResponses(functionResponses).build());
  }

  /** Extracts FunctionResponse parts from a Content object if all parts are FunctionResponses. */
  private List<FunctionResponse> extractFunctionResponses(Content content) {
    if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList<FunctionResponse> responses =
        content.parts().get().stream()
            .map(Part::functionResponse)
            .flatMap(Optional::stream)
            .collect(toImmutableList());

    // Ensure *all* parts were function responses.
    return (responses.size() == content.parts().get().size()) ? responses : ImmutableList.of();
  }

  @Override
  public Completable sendRealtime(Blob blob) {
    return Completable.fromFuture(
        transportFuture.thenCompose(
            transport ->
                transport.sendRealtimeInput(
                    LiveSendRealtimeInputParameters.builder().media(blob).build())));
  }

  /** Helper to send client content parameters. */
  private Completable sendClientContentInternal(LiveSendClientContentParameters parameters) {
    return Completable.fromFuture(
        transportFuture.thenCompose(transport -> transport.sendClientContent(parameters)));
  }

  /** Helper to send tool response parameters. */
  private Completable sendToolResponseInternal(LiveSendToolResponseParameters parameters) {
    return Completable.fromFuture(
        transportFuture.thenCompose(transport -> transport.sendToolResponse(parameters)));
  }

  @Override
  public Flowable<LlmResponse> receive() {
    return responseFlowable;
  }

  @Override
  public void close() {
    closeInternal(null);
  }

  @Override
  public void close(Throwable throwable) {
    Objects.requireNonNull(throwable, "throwable cannot be null for close");
    closeInternal(throwable);
  }

  /** Internal method to handle closing logic and signal completion/error. */
  private void closeInternal(Throwable throwable) {
    if (closed.compareAndSet(false, true)) {
      logger.debug("Closing GeminiConnection.", throwable);

      if (throwable == null) {
        responseProcessor.onComplete();
      } else {
        responseProcessor.onError(throwable);
      }

      if (transportFuture.isDone()) {
        transportFuture
            .thenAccept(this::closeTransportIgnoringErrors)
            .exceptionally(unusedError -> null);
      } else {
        transportFuture.cancel(false);
      }

      disposables.dispose();
    }
  }

  /** Closes the transport safely, logging any errors. */
  private void closeTransportIgnoringErrors(GeminiLiveTransport transport) {
    if (transport != null) {
      transport
          .close()
          .exceptionally(
              closeError -> {
                logger.warn("Error occurred while closing live transport", closeError);
                return null; // Suppress error during close
              });
    }
  }
}
