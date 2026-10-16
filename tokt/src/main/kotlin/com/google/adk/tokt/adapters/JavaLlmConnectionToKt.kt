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
 * chunks final, so they are re-marked partial with one aggregate per utterance.
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
        for (aggregatedResponse in aggregateTranscriptions(response)) emit(aggregatedResponse)
        // One turn per collection: it ends after the response that completes the turn.
        if (response.turnComplete == true) break
      }
    } finally {
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
   * Returns the responses to emit for [response]: its transcription chunks re-marked partial, and
   * each utterance's finished aggregate once the utterance ends, on a finished chunk or before the
   * response that ends speech.
   */
  private fun aggregateTranscriptions(response: LlmResponse): List<LlmResponse> {
    val endsSpeech = response.turnComplete == true || response.interrupted
    val input = response.inputTranscription
    val output = response.outputTranscription
    if (response.partial || (input == null && output == null)) {
      return if (endsSpeech) flushTranscripts() + response else listOf(response)
    }
    val rest = response.copy(inputTranscription = null, outputTranscription = null)
    val carriesMore = rest.copy(turnComplete = null) != LlmResponse()
    return buildList {
      if (carriesMore && !endsSpeech) add(rest)
      if (input != null) addAll(inputTranscript.accept(input))
      if (output != null) addAll(outputTranscript.accept(output))
      if (endsSpeech) {
        addAll(flushTranscripts())
        add(rest)
      }
    }
  }

  private fun flushTranscripts(): List<LlmResponse> =
    listOfNotNull(inputTranscript.aggregate(), outputTranscript.aggregate())

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
