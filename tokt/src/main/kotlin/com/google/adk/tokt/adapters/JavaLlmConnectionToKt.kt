/*
 * Copyright 2026 Google LLC
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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.tokt.adapters

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Transcription
import com.google.adk.models.BaseLlmConnection as JavaBaseLlmConnection
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.LlmResponseCodec
import com.google.adk.tokt.codecs.PartCodec
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Java -> Kotlin adapter: presents an ADK Java [JavaBaseLlmConnection] as a Kotlin
 * [LiveConnection], so the Kotlin live loop can drive a Java model's live session. Java's
 * `receive()` is one stream that drops what arrives before a subscriber, so it is subscribed once,
 * here, and buffered until the engine collects each turn. `GeminiLlmConnection` marks transcription
 * and model text chunks final, so they are re-marked partial, each run then sent as one aggregate.
 */
internal class JavaLlmConnectionToKt(
  private val javaConnection: JavaBaseLlmConnection,
  private val dispatcher: CoroutineDispatcher,
  private val closeTimeout: Duration = CLOSE_TIMEOUT,
) : LiveConnection {

  private val closed = AtomicBoolean(false)
  private val responses = Channel<LlmResponse>(Channel.UNLIMITED)
  private val subscription: Disposable =
    javaConnection
      .receive()
      .subscribe(
        { responses.trySend(LlmResponseCodec.fromJava(it)) },
        // A Java close may fail its stream, and closeSession must still end collectors normally.
        { if (closed.get()) responses.close() else responses.close(it) },
        { responses.close() },
      )
  private val collecting = AtomicBoolean(false)
  // Touched only by receive, which `collecting` keeps to one collector at a time.
  private val inputTranscript = Transcript { LlmResponse(inputTranscription = it) }
  private val outputTranscript = Transcript { LlmResponse(outputTranscription = it) }
  private val modelText = ModelText()

  override suspend fun sendHistory(history: List<Content>) {
    send { javaConnection.sendHistory(history.map { ContentCodec.toJava(it) }) }
  }

  override suspend fun sendContent(content: Content, partial: Boolean) {
    // Enforces the LiveConnection contract: invalid content throws before anything is sent.
    val input = ContentInput(content, partial)
    send { javaConnection.sendContent(ContentCodec.toJava(input.content), input.partial) }
  }

  override suspend fun sendRealtime(input: RealtimeInput) {
    val blob =
      when (input) {
        is RealtimeInput.Audio -> input.blob
        is RealtimeInput.Video -> input.blob
        RealtimeInput.ActivityStart,
        RealtimeInput.ActivityEnd,
        RealtimeInput.AudioStreamEnd ->
          throw UnsupportedOperationException(
            "An ADK Java live connection cannot send ${input::class.simpleName}"
          )
      }
    send { javaConnection.sendRealtime(PartCodec.blobToJava(blob)) }
  }

  override fun receive(): Flow<LlmResponse> = flow {
    check(collecting.compareAndSet(false, true)) {
      "LiveConnection.receive() allows one collector at a time, because an ADK Java connection " +
        "is a single stream of responses."
    }
    try {
      for (response in responses) {
        for (aggregatedResponse in aggregateResponse(response)) emit(aggregatedResponse)
        // One turn per collection: it ends after the response that completes the turn.
        if (response.turnComplete == true) break
      }
    } finally {
      // Text from a collection stopped mid-turn is not carried into the next collection.
      modelText.reset()
      collecting.set(false)
    }
  }

  /**
   * Waits at most [closeTimeout] for the Java close, because a blocking Java call cannot be
   * cancelled. Collectors end either way.
   */
  override suspend fun closeSession() {
    if (!closed.compareAndSet(false, true)) return
    try {
      // A cancelled caller still waits for the Java close, up to the timeout.
      withContext(NonCancellable) { closeJavaConnection(javaConnection, dispatcher, closeTimeout) }
    } finally {
      // Ends collectors even if the Java stream never completes.
      subscription.dispose()
      responses.close()
    }
  }

  /**
   * Starts a Java send on [dispatcher], where a blocking Java call is safe, and subscribes to it
   * there as well, because ADK Java's sends block their subscriber until sent. The caller then only
   * suspends, so it stays cancellable when a dead Java session never completes the send.
   */
  private suspend fun send(start: () -> Completable) {
    currentCoroutineContext().ensureActive()
    val sent = CompletableFuture<Unit>()
    dispatcher.asExecutor().execute {
      // Done here only if the caller gave up while this waited for the dispatcher.
      if (sent.isDone) return@execute
      try {
        // Never disposed: an error after the caller gave up would otherwise reach RxJavaPlugins.
        val unused = start().subscribe({ sent.complete(Unit) }, { sent.completeExceptionally(it) })
      } catch (e: Throwable) {
        sent.completeExceptionally(e)
      }
    }
    sent.await()
  }

  /**
   * The responses to emit for [response]: its model text, then its transcriptions, re-marked and
   * aggregated.
   */
  private fun aggregateResponse(response: LlmResponse): List<LlmResponse> {
    val split = takeModelText(response)
    return split.leading + aggregateTranscriptions(split.rest)
  }

  /**
   * Moves [response]'s model text into the turn's aggregate, since `GeminiLlmConnection` marks
   * every text chunk final; what that flushes goes out before the rest of [response]. Text a stream
   * already marks partial, and the aggregate it sends after such chunks, pass through unchanged.
   */
  private fun takeModelText(response: LlmResponse): ModelTextSplit {
    val content = response.content ?: return ModelTextSplit(emptyList(), response)
    val parts = content.parts
    if (response.partial) {
      if (parts.any { it.text != null }) modelText.markStreamChunked()
      return ModelTextSplit(emptyList(), response)
    }
    if (parts.any { it.functionCall != null }) {
      return ModelTextSplit(listOfNotNull(modelText.flush()), response)
    }
    if (parts.isNotEmpty() && parts.all { it.isText } && modelText.isOwnAggregate) {
      modelText.reset()
      return ModelTextSplit(emptyList(), response)
    }
    val chunk = mutableListOf<Part>()
    val kept = mutableListOf<Part>()
    val leading = buildList {
      for (part in parts) {
        when {
          part.isText -> {
            if (modelText.switchesThought(part)) {
              chunk.clear()
              modelText.flush()?.let { add(it) }
            }
            chunk.add(part)
            modelText.append(part)
          }
          // Audio streams alongside the text, so it does not end it.
          part.inlineData != null -> kept.add(part)
          else -> {
            chunk.clear()
            modelText.flush()?.let { add(it) }
            kept.add(part)
          }
        }
      }
      // A kept part other than audio came before the unflushed text, so the kept parts go first.
      if (chunk.isNotEmpty() && kept.any { it.inlineData == null }) {
        add(LlmResponse(content = content.copy(parts = kept.toList())))
        kept.clear()
      }
      // A response that ends speech flushes its text, so it sends no chunk.
      if (chunk.isNotEmpty() && response.turnComplete != true && !response.interrupted) {
        add(LlmResponse(content = content.copy(parts = chunk), partial = true))
      }
    }
    val rest = response.copy(content = if (kept.isEmpty()) null else content.copy(parts = kept))
    return ModelTextSplit(leading, rest)
  }

  /**
   * Returns the responses to emit for [response], whose model text is already taken: its
   * transcription chunks re-marked partial and each utterance's finished aggregate once the
   * utterance ends. When speech ends, the transcript and text aggregates come before [response].
   */
  private fun aggregateTranscriptions(response: LlmResponse): List<LlmResponse> {
    val endsSpeech = response.turnComplete == true || response.interrupted
    val input = response.inputTranscription
    val output = response.outputTranscription
    if (response.partial || (input == null && output == null)) {
      return when {
        endsSpeech -> flushTranscripts() + flushModelTextBefore(response)
        // Only flags are left, for example once its text was taken: nothing to send.
        response.isOnlyFlags -> emptyList()
        else -> listOf(response)
      }
    }
    val rest = response.copy(inputTranscription = null, outputTranscription = null)
    val carriesMore = rest.copy(turnComplete = null) != LlmResponse()
    return buildList {
      if (carriesMore && !endsSpeech) add(rest)
      if (input != null) addAll(inputTranscript.accept(input))
      if (output != null) addAll(outputTranscript.accept(output))
      if (endsSpeech) {
        addAll(flushTranscripts())
        addAll(flushModelTextBefore(rest))
      }
    }
  }

  /**
   * The turn's text aggregate, then [response]. A bare interruption marker is dropped, since the
   * aggregate carries `interrupted` in its place.
   */
  private fun flushModelTextBefore(response: LlmResponse): List<LlmResponse> {
    val text = modelText.flush(interrupted = response.interrupted) ?: return listOf(response)
    val bareMarker = response.interrupted && response.turnComplete != true && response.isOnlyFlags
    return if (bareMarker) listOf(text) else listOf(text, response)
  }

  private fun flushTranscripts(): List<LlmResponse> =
    listOfNotNull(inputTranscript.aggregate(), outputTranscript.aggregate())

  /** The model's text since its last aggregate; `GeminiLlmConnection` sends no grounding. */
  private class ModelText {
    private val text = StringBuilder()
    private var thought = false
    private var remarked = false
    private var streamChunked = false

    /**
     * Whether a final text response is the stream's own aggregate: the stream marked its text
     * chunks partial, and no final chunk was taken since the last aggregate.
     */
    val isOwnAggregate: Boolean
      get() = streamChunked && !remarked

    fun markStreamChunked() {
      streamChunked = true
    }

    fun switchesThought(part: Part): Boolean =
      text.isNotEmpty() && thought != (part.thought == true)

    fun append(part: Part) {
      text.append(part.text.orEmpty())
      thought = part.thought == true
      remarked = true
    }

    /** The finished text so far as one response, or null if there is none; then resets. */
    fun flush(interrupted: Boolean = false): LlmResponse? {
      val aggregated = text.toString()
      val wasThought = thought
      reset()
      if (aggregated.isEmpty()) return null
      val part = Part(text = aggregated, thought = if (wasThought) true else null)
      return LlmResponse(
        content = Content(role = Role.MODEL, parts = listOf(part)),
        interrupted = interrupted,
      )
    }

    fun reset() {
      text.clear()
      thought = false
      remarked = false
      streamChunked = false
    }
  }

  /** One direction's transcript since its last aggregate, each response built by [wrap]. */
  private class Transcript(private val wrap: (Transcription) -> LlmResponse) {
    private val text = StringBuilder()

    /**
     * Returns the responses for [chunk] of a response not marked partial: the chunk re-marked
     * partial, then the aggregate if the chunk is finished. A finished chunk with text and nothing
     * before it is the stream's own aggregate, so it passes through unchanged.
     */
    fun accept(chunk: Transcription): List<LlmResponse> {
      if (chunk.finished == true && text.isEmpty() && !chunk.text.isNullOrEmpty()) {
        return listOf(wrap(chunk))
      }
      text.append(chunk.text.orEmpty())
      return buildList {
        if (!chunk.text.isNullOrEmpty()) {
          add(wrap(chunk.copy(finished = false)).copy(partial = true))
        }
        if (chunk.finished == true) aggregate()?.let { add(it) }
      }
    }

    /** The finished transcript of the utterance so far, or null if it is empty; then resets. */
    fun aggregate(): LlmResponse? {
      val aggregated = text.toString()
      text.clear()
      if (aggregated.isEmpty()) return null
      return wrap(Transcription(text = aggregated, finished = true))
    }
  }
}

/** Whether this part is text rather than media. */
private val Part.isText: Boolean
  get() = text != null && inlineData == null

/** Whether this response carries nothing but its partial, turn-complete and interrupted flags. */
private val LlmResponse.isOnlyFlags: Boolean
  get() = copy(partial = false, turnComplete = null, interrupted = false) == LlmResponse()

/** A response's model text split off: what goes out first, then what is left of the response. */
private class ModelTextSplit(val leading: List<LlmResponse>, val rest: LlmResponse)

/**
 * Starts [connection]'s close on [dispatcher] and waits at most [timeout] for it, rethrowing a
 * close failure. Only the wait is bounded: a close that starts late still runs.
 */
internal suspend fun closeJavaConnection(
  connection: JavaBaseLlmConnection,
  dispatcher: CoroutineDispatcher,
  timeout: Duration = CLOSE_TIMEOUT,
) {
  val javaClose = CompletableFuture.runAsync({ connection.close() }, dispatcher.asExecutor())
  // A Deferred: a timed-out CompletableFuture.await() would cancel a close not yet started.
  val finished =
    withTimeoutOrNull(timeout) {
      javaClose.asDeferred().await()
      true
    }
  if (finished == null) {
    logger.warn {
      "An ADK Java live connection did not close within $timeout; not waiting further."
    }
  }
}

/** How long `closeJavaConnection` waits for `JavaBaseLlmConnection.close()` before returning. */
private val CLOSE_TIMEOUT = 10.seconds

private val logger = LoggerFactory.getLogger(JavaLlmConnectionToKt::class)
