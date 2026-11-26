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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GroundingChunk;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.GroundingSupport;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import com.google.genai.types.UsageMetadata;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
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
  // Both used only by handleServerMessage, which genai's WebSocket listener calls one at a time.
  private final PendingText pendingText = new PendingText();
  private final PendingTranscriptions pendingTranscriptions;

  /**
   * Establishes a new connection over the given live transport.
   *
   * @param modelName The name of the model the transport is connected to.
   * @param transportFuture The live transport the connection drives, once established.
   */
  GeminiLlmConnection(String modelName, CompletableFuture<GeminiLiveTransport> transportFuture) {
    this.pendingTranscriptions = new PendingTranscriptions(isGemini3xLive(modelName));
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

    Observable<LlmResponse> llmResponse =
        convertToServerResponse(message, pendingText, pendingTranscriptions);
    if (!disposables.add(
        llmResponse.subscribe(responseProcessor::onNext, responseProcessor::onError))) {
      logger.warn(
          "disposables container already disposed, the subscription will be disposed immediately");
    }
  }

  /**
   * Converts a server message into the standardized LlmResponse format.
   *
   * @param pendingText the text streamed by earlier messages and not yet emitted in full
   * @param pendingTranscriptions the transcriptions streamed by earlier messages and not yet
   *     emitted in full
   */
  static Observable<LlmResponse> convertToServerResponse(
      LiveServerMessage message,
      PendingText pendingText,
      PendingTranscriptions pendingTranscriptions) {
    return Observable.create(emitter -> {
      // AtomicBoolean is used to modify state from within lambdas, which
      // require captured variables to be effectively final.
      final AtomicBoolean handled = new AtomicBoolean(false);
      message
          .serverContent()
          .ifPresent(serverContent -> {
            emitServerContentResponses(serverContent, pendingText, pendingTranscriptions, emitter);
            handled.set(true);
          });
      message
          .toolCall()
          .ifPresent(toolCall -> {
            // Emit pending speech first so a transfer the call starts cannot drop it.
            pendingTranscriptions.flushBeforeToolCall(emitter);
            pendingText.flush().ifPresent(emitter::onNext);
            emitter.onNext(createToolCallResponse(toolCall, pendingText.takeGrounding()));
            handled.set(true);
          });
      message
          .usageMetadata()
          .ifPresent(usageMetadata -> {
            logger.debug("Received usage metadata: {}", usageMetadata);
            emitter.onNext(createUsageMetadataResponse(usageMetadata));
            handled.set(true);
          });
      message
          .toolCallCancellation()
          .ifPresent(toolCallCancellation -> {
            logger.debug("Received tool call cancellation: {}", toolCallCancellation);
            // TODO: implement proper CFC and thus tool call cancellation handling.
            handled.set(true);
          });
      message
          .setupComplete()
          .ifPresent(setupComplete -> {
            logger.debug("Received setup complete.");
            handled.set(true);
          });

      if (!handled.get()) {
        logger.warn("Received unknown or empty server message.");
        emitter.onNext(createUnknownMessageResponse());
      }
      emitter.onComplete();
    });
  }

  /**
   * Emits the responses for a server content message. Text is streamed in partial responses and
   * emitted again in full when other content, the end of the turn or an interruption ends it; text
   * that arrives with the end of the turn or an interruption is emitted only in full.
   * Transcriptions get responses of their own, emitted before the full text and the end of the
   * turn, and grounding metadata merged across the turn goes to the full text, a tool call or the
   * end of the turn, as in ADK Python.
   */
  private static void emitServerContentResponses(
      LiveServerContent serverContent,
      PendingText pendingText,
      PendingTranscriptions pendingTranscriptions,
      ObservableEmitter<LlmResponse> emitter) {
    Optional<GroundingMetadata> grounding = serverContent.groundingMetadata();
    grounding.ifPresent(pendingText::addGrounding);
    List<Part> parts = serverContent.modelTurn().flatMap(Content::parts).orElse(ImmutableList.of());
    List<Part> streamedParts = new ArrayList<>();
    for (Part part : parts) {
      if (pendingText.isEndedBy(part)) {
        // A thought/answer switch leaves grounding for the final text or turn end.
        pendingText
            .flush(/* interrupted= */ false, /* withGrounding= */ !hasText(part))
            .ifPresent(emitter::onNext);
        // The full response now carries the text parts of this message so far.
        streamedParts.removeIf(GeminiLlmConnection::hasText);
      }
      if (hasText(part)) {
        pendingText.append(part);
      }
      streamedParts.add(part);
    }
    boolean turnComplete = serverContent.turnComplete().orElse(false);
    boolean interrupted = serverContent.interrupted().orElse(false);
    boolean interruptionInFullText = false;
    if (turnComplete || interrupted) {
      streamedParts.removeIf(GeminiLlmConnection::hasText);
      pendingTranscriptions.receive(serverContent, emitter);
      if (turnComplete) {
        warnIfIncomplete(grounding.or(pendingText::grounding));
      }
      Optional<LlmResponse> fullText = pendingText.flush(interrupted, /* withGrounding= */ true);
      fullText.ifPresent(emitter::onNext);
      interruptionInFullText =
          interrupted && fullText.isPresent() && !turnComplete && streamedParts.isEmpty();
    }

    LlmResponse.Builder builder = LlmResponse.builder();
    if (streamedParts.size() == parts.size()) {
      serverContent.modelTurn().ifPresent(builder::content);
    } else if (!streamedParts.isEmpty()) {
      builder.content(serverContent.modelTurn().get().toBuilder().parts(streamedParts).build());
    }
    // Only text is partial: it is emitted again in full.
    boolean streamsText = streamedParts.stream().anyMatch(GeminiLlmConnection::hasText);
    if (turnComplete) {
      // This message's grounding, else what the turn collected and no full text carried.
      Optional<GroundingMetadata> turnGrounding = pendingText.takeGrounding();
      builder.groundingMetadata(grounding.or(() -> turnGrounding).orElse(null));
    } else if (interrupted) {
      builder.groundingMetadata(pendingText.takeGrounding().orElse(null));
    } else {
      // Streamed chunks carry their own message's grounding; a message may carry nothing else.
      builder.groundingMetadata(grounding.orElse(null));
    }
    LlmResponse response =
        builder
            .partial(streamsText)
            .turnComplete(turnComplete)
            .interrupted(interruptionInFullText ? null : serverContent.interrupted().orElse(null))
            .build();
    if (turnComplete || interrupted) {
      if (!interruptionInFullText) {
        emitter.onNext(response);
      }
      return;
    }
    if (response.content().isPresent() || response.groundingMetadata().isPresent()) {
      emitter.onNext(response);
    }
    pendingTranscriptions.receive(serverContent, emitter);
  }

  /** Logs ADK Python's warning for turn grounding that has retrieval queries but no chunks. */
  private static void warnIfIncomplete(Optional<GroundingMetadata> grounding) {
    grounding
        .filter(g -> !g.retrievalQueries().orElse(ImmutableList.of()).isEmpty())
        .filter(g -> g.groundingChunks().orElse(ImmutableList.of()).isEmpty())
        .ifPresent(g ->
            logger.warn(
                "Incomplete grounding metadata received: {} retrieval queries but no grounding"
                    + " chunks. This may indicate a transient issue with the Vertex AI Search"
                    + " backend.",
                g.retrievalQueries().get().size()));
  }

  /**
   * Returns whether {@code modelName} names a Gemini 3.x Live model, which sends each input
   * transcription once, in full.
   */
  static boolean isGemini3xLive(@Nullable String modelName) {
    String name = Strings.nullToEmpty(modelName);
    String modelId = name.substring(name.lastIndexOf('/') + 1);
    return modelId.startsWith("gemini-3.")
        && modelId.contains("-live")
        && !modelId.startsWith("gemini-3.5-live-translate");
  }

  /** Returns whether {@code part} has text; an empty string does not count. */
  private static boolean hasText(Part part) {
    return part.text().filter(text -> !text.isEmpty()).isPresent();
  }

  private static LlmResponse createToolCallResponse(
      LiveServerToolCall toolCall, Optional<GroundingMetadata> grounding) {
    LlmResponse.Builder builder = LlmResponse.builder().groundingMetadata(grounding.orElse(null));
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

  /**
   * Sends {@code history} without audio, which a live session cannot replay, or {@code _adk_live}
   * artifact references, which the model cannot resolve. Completes the turn so the model answers
   * only when the remaining history ends with a user turn.
   */
  @Override
  public Completable sendHistory(List<Content> history) {
    ImmutableList<Content> contents =
        history.stream()
            .map(GeminiLlmConnection::filterReplayableParts)
            .flatMap(Optional::stream)
            .collect(toImmutableList());
    if (contents.isEmpty()) {
      logger.debug("No history to send after dropping audio and _adk_live artifact references.");
      return Completable.complete();
    }
    boolean turnComplete = Iterables.getLast(contents).role().map("user"::equals).orElse(false);
    return sendClientContentInternal(
        LiveSendClientContentParameters.builder()
            .turns(contents)
            .turnComplete(turnComplete)
            .build());
  }

  /** Returns {@code content} with only its replayable parts, or empty if none remain. */
  private static Optional<Content> filterReplayableParts(Content content) {
    ImmutableList<Part> parts =
        content.parts().orElse(ImmutableList.of()).stream()
            .filter(part -> !isAudio(part) && !isAdkLiveArtifact(part))
            .collect(toImmutableList());
    return parts.isEmpty()
        ? Optional.empty()
        : Optional.of(content.toBuilder().parts(parts).build());
  }

  private static boolean isAudio(Part part) {
    return part.inlineData().flatMap(Blob::mimeType).map(m -> m.startsWith("audio/")).orElse(false)
        || part.fileData()
            .flatMap(FileData::mimeType)
            .map(m -> m.startsWith("audio/"))
            .orElse(false);
  }

  /**
   * Matches the {@code _adk_live} references that ADK Python and ADK Kotlin live runs save to a
   * session.
   */
  private static boolean isAdkLiveArtifact(Part part) {
    return part.fileData()
        .flatMap(FileData::fileUri)
        .map(uri -> uri.startsWith("artifact://") && uri.contains("/_adk_live/"))
        .orElse(false);
  }

  @Override
  public Completable sendContent(Content content) {
    return sendContent(content, /* partial= */ false);
  }

  @Override
  public Completable sendContent(Content content, boolean partial) {
    Objects.requireNonNull(content, "content cannot be null");

    List<FunctionResponse> functionResponses = extractFunctionResponses(content);
    if (functionResponses.isEmpty()) {
      return sendClientContentInternal(
          LiveSendClientContentParameters.builder()
              .turns(ImmutableList.of(content))
              .turnComplete(!partial)
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

  /**
   * Input and output transcription text that has been streamed in partial responses but not yet
   * emitted in full.
   */
  static final class PendingTranscriptions {
    private final boolean inputArrivesInFull;
    private final StringBuilder input = new StringBuilder();
    private final StringBuilder output = new StringBuilder();
    // Set on a tool-call flush until new text or a finish arrives, to skip empty finished events.
    private boolean inputEmittedEarly;
    private boolean outputEmittedEarly;

    /**
     * Creates the pending state for one connection.
     *
     * @param inputArrivesInFull whether each input transcription is already complete, as Gemini 3.x
     *     Live models send it
     */
    PendingTranscriptions(boolean inputArrivesInFull) {
      this.inputArrivesInFull = inputArrivesInFull;
    }

    /**
     * Streams each transcription chunk in {@code serverContent} in a partial response, and emits
     * the full text in a finished, non-partial one when the transcription, the generation or the
     * turn ends, or the model is interrupted.
     */
    private void receive(LiveServerContent serverContent, ObservableEmitter<LlmResponse> emitter) {
      serverContent
          .inputTranscription()
          .ifPresent(transcription -> {
            if (inputArrivesInFull) {
              transcription
                  .text()
                  .filter(text -> !text.isEmpty())
                  .ifPresent(text -> emitter.onNext(inputResponse(text, /* finished= */ true)));
            } else {
              inputEmittedEarly =
                  stream(
                      transcription,
                      input,
                      inputEmittedEarly,
                      emitter,
                      PendingTranscriptions::inputResponse);
            }
          });
      serverContent
          .outputTranscription()
          .ifPresent(transcription ->
              outputEmittedEarly =
                  stream(
                      transcription,
                      output,
                      outputEmittedEarly,
                      emitter,
                      PendingTranscriptions::outputResponse));
      // The Live API may end a transcription without marking it finished.
      if (serverContent.interrupted().orElse(false)
          || serverContent.turnComplete().orElse(false)
          || serverContent.generationComplete().orElse(false)) {
        flush(emitter);
      }
    }

    /** Emits pending input and output text in full as finished, non-partial responses. */
    private void flush(ObservableEmitter<LlmResponse> emitter) {
      flush(input, emitter, PendingTranscriptions::inputResponse);
      flush(output, emitter, PendingTranscriptions::outputResponse);
    }

    private static void flush(
        StringBuilder pending,
        ObservableEmitter<LlmResponse> emitter,
        TranscriptionResponseFactory responseFactory) {
      if (pending.length() > 0) {
        emitter.onNext(responseFactory.create(pending.toString(), /* finished= */ true));
        pending.setLength(0);
      }
    }

    /**
     * Emits pending text in full before a tool call and records the early flush so a later finish
     * with no new text is skipped.
     */
    private void flushBeforeToolCall(ObservableEmitter<LlmResponse> emitter) {
      inputEmittedEarly |= input.length() > 0;
      outputEmittedEarly |= output.length() > 0;
      flush(emitter);
    }

    /**
     * Streams the chunk in {@code transcription} and emits the accumulated text once finished.
     * Returns the updated {@code emittedEarly} flag, which stays true only if an early tool-call
     * flush is still the latest emitted text with no new text or finish since.
     */
    private static boolean stream(
        Transcription transcription,
        StringBuilder pending,
        boolean emittedEarly,
        ObservableEmitter<LlmResponse> emitter,
        TranscriptionResponseFactory responseFactory) {
      String chunk = transcription.text().orElse("");
      if (!chunk.isEmpty()) {
        pending.append(chunk);
        emitter.onNext(responseFactory.create(chunk, /* finished= */ false));
      }
      if (!transcription.finished().orElse(false)) {
        return emittedEarly && pending.isEmpty();
      }
      // A finish with no new text after a tool-call flush would emit an empty finished event.
      if (pending.length() > 0 || !emittedEarly) {
        emitter.onNext(responseFactory.create(pending.toString(), /* finished= */ true));
      }
      pending.setLength(0);
      return false;
    }

    private static LlmResponse inputResponse(String text, boolean finished) {
      return LlmResponse.builder()
          .inputTranscription(transcription(text, finished))
          .partial(!finished)
          .build();
    }

    private static LlmResponse outputResponse(String text, boolean finished) {
      return LlmResponse.builder()
          .outputTranscription(transcription(text, finished))
          .partial(!finished)
          .build();
    }

    private static Transcription transcription(String text, boolean finished) {
      return Transcription.builder().text(text).finished(finished).build();
    }

    private interface TranscriptionResponseFactory {
      LlmResponse create(String text, boolean finished);
    }
  }

  /**
   * Model text that has been streamed in partial responses but not yet emitted in full, and the
   * grounding metadata the turn has collected but no response has carried yet.
   */
  static final class PendingText {
    private final StringBuilder text = new StringBuilder();
    private boolean thought;
    private @Nullable GroundingMetadata grounding;

    /**
     * Returns whether {@code part} ends the pending text: text of the other thought kind, or a part
     * that is neither text nor inline data.
     */
    private boolean isEndedBy(Part part) {
      if (text.length() == 0) {
        return false;
      }
      return hasText(part) ? part.thought().orElse(false) != thought : part.inlineData().isEmpty();
    }

    private void append(Part part) {
      text.append(part.text().get());
      thought = part.thought().orElse(false);
    }

    /** Merges {@code added} into the grounding collected so far. */
    private void addGrounding(GroundingMetadata added) {
      grounding = grounding == null ? added : merge(grounding, added);
    }

    private Optional<GroundingMetadata> grounding() {
      return Optional.ofNullable(grounding);
    }

    /** Returns the collected grounding and clears it. */
    private Optional<GroundingMetadata> takeGrounding() {
      Optional<GroundingMetadata> taken = grounding();
      grounding = null;
      return taken;
    }

    /**
     * Returns the pending text as one non-partial response with the collected grounding and clears
     * both, or empty if there is no text.
     */
    private Optional<LlmResponse> flush() {
      return flush(/* interrupted= */ false, /* withGrounding= */ true);
    }

    /**
     * Returns the pending text as one non-partial response and clears it, or empty if none.
     *
     * @param interrupted whether an interruption ended the text, which the response then reports
     * @param withGrounding whether the response carries the collected grounding, which is then
     *     cleared
     */
    private Optional<LlmResponse> flush(boolean interrupted, boolean withGrounding) {
      if (text.length() == 0) {
        return Optional.empty();
      }
      Optional<GroundingMetadata> responseGrounding =
          withGrounding ? takeGrounding() : Optional.empty();
      Part.Builder part = Part.builder().text(text.toString());
      if (thought) {
        part.thought(true);
      }
      text.setLength(0);
      return Optional.of(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(part.build()).build())
              .partial(false)
              .interrupted(interrupted ? true : null)
              .groundingMetadata(responseGrounding.orElse(null))
              .build());
    }

    /**
     * Merges {@code added} into {@code existing} as ADK Python's {@code _merge_grounding_metadata}
     * does: query lists gain the added queries they lack, chunks and supports are appended, and
     * other fields set in {@code added} replace those in {@code existing}. The chunk indices of the
     * added supports are shifted past the existing chunks, so each still cites its own source.
     */
    private static GroundingMetadata merge(GroundingMetadata existing, GroundingMetadata added) {
      GroundingMetadata.Builder merged = existing.toBuilder();
      int chunkOffset = existing.groundingChunks().map(List::size).orElse(0);
      added
          .imageSearchQueries()
          .ifPresent(q -> merged.imageSearchQueries(union(existing.imageSearchQueries(), q)));
      added
          .webSearchQueries()
          .ifPresent(q -> merged.webSearchQueries(union(existing.webSearchQueries(), q)));
      added
          .retrievalQueries()
          .ifPresent(q -> merged.retrievalQueries(union(existing.retrievalQueries(), q)));
      added
          .groundingChunks()
          .ifPresent(chunks ->
              merged.groundingChunks(
                  ImmutableList.<GroundingChunk>builder()
                      .addAll(existing.groundingChunks().orElse(ImmutableList.of()))
                      .addAll(chunks)
                      .build()));
      added
          .groundingSupports()
          .ifPresent(supports ->
              merged.groundingSupports(
                  ImmutableList.<GroundingSupport>builder()
                      .addAll(existing.groundingSupports().orElse(ImmutableList.of()))
                      .addAll(
                          supports.stream()
                              .map(support -> shiftChunkIndices(support, chunkOffset))
                              .iterator())
                      .build()));
      added.retrievalMetadata().ifPresent(merged::retrievalMetadata);
      added.searchEntryPoint().ifPresent(merged::searchEntryPoint);
      added.googleMapsWidgetContextToken().ifPresent(merged::googleMapsWidgetContextToken);
      added.sourceFlaggingUris().ifPresent(merged::sourceFlaggingUris);
      return merged.build();
    }

    /** Returns {@code existing} followed by the items of {@code added} it does not contain yet. */
    private static ImmutableList<String> union(
        Optional<List<String>> existing, List<String> added) {
      List<String> union = new ArrayList<>(existing.orElse(ImmutableList.of()));
      for (String item : added) {
        if (!union.contains(item)) {
          union.add(item);
        }
      }
      return ImmutableList.copyOf(union);
    }

    private static GroundingSupport shiftChunkIndices(GroundingSupport support, int offset) {
      if (offset == 0 || support.groundingChunkIndices().orElse(ImmutableList.of()).isEmpty()) {
        return support;
      }
      return support.toBuilder()
          .groundingChunkIndices(
              support.groundingChunkIndices().get().stream()
                  .map(index -> index + offset)
                  .collect(toImmutableList()))
          .build();
    }
  }
}
