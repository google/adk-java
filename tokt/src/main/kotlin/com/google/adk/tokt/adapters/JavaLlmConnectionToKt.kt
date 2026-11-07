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
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content
import com.google.adk.models.BaseLlmConnection as JavaBaseLlmConnection
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.LlmResponseCodec
import com.google.adk.tokt.codecs.PartCodec
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext

/**
 * Java -> Kotlin adapter: presents an ADK Java [JavaBaseLlmConnection] as a Kotlin
 * [LiveConnection], so the Kotlin live loop can drive a Java model's live session. Java's
 * `receive()` is one stream that drops what arrives before a subscriber, so it is subscribed once,
 * here, and buffered until the engine collects each turn.
 */
internal class JavaLlmConnectionToKt(
  private val javaConnection: JavaBaseLlmConnection,
  private val dispatcher: CoroutineDispatcher,
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
        emit(response)
        // One turn per collection: it ends after the response that completes the turn.
        if (response.turnComplete == true) break
      }
    } finally {
      collecting.set(false)
    }
  }

  override suspend fun closeSession() {
    if (!closed.compareAndSet(false, true)) return
    try {
      withContext(dispatcher) { javaConnection.close() }
    } finally {
      // Ends collectors even if the Java stream never completes.
      subscription.dispose()
      responses.close()
    }
  }

  /** Runs a Java send on [dispatcher], where a blocking Java call is safe, until it completes. */
  private suspend fun send(start: () -> Completable) {
    withContext(dispatcher) { start().await() }
  }
}
