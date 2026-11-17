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

package com.google.adk.tokt.adapters

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.tokt.codecs.LlmRequestCodec
import com.google.adk.tokt.codecs.LlmResponseCodec
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext

/**
 * Java -> Kotlin adapter: presents a user's ADK Java [JavaBaseLlm] as a Kotlin [Model] so the ADK
 * Kotlin agent loop can call it. The model seam for routing ADK Java's `LlmAgent` onto the Kotlin
 * engine.
 *
 * The Kotlin `LlmRequest` is converted to a Java `LlmRequest` ([LlmRequestCodec]), the Java model's
 * RxJava `Flowable<LlmResponse>` is consumed as a coroutine [Flow] (via
 * kotlinx-coroutines-reactive) on `dispatcher` and each response is converted back
 * ([LlmResponseCodec]).
 */
internal class JavaModelToKt(
  private val javaLlm: JavaBaseLlm,
  private val dispatcher: CoroutineDispatcher,
) : Model {

  override val name: String = javaLlm.model()

  // Deferred into `flow {}` and run on dispatcher so the Java model's synchronous RxJava generation
  // (and any synchronous throw, routed through the Flow's error channel) stays off the engine loop.
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    flow {
        emitAll(
          javaLlm.generateContent(LlmRequestCodec.toJava(request), stream).asFlow().map {
            LlmResponseCodec.fromJava(it)
          }
        )
      }
      .flowOn(dispatcher)

  // A Java connect may block: run it on dispatcher, and close what it opens if the caller gave up.
  @OptIn(ExperimentalLiveApi::class)
  override suspend fun connect(request: LlmRequest): LiveConnection {
    if (
      request.liveConnectConfig.realtimeInputConfig?.automaticActivityDetection?.disabled == true
    ) {
      throw UnsupportedOperationException(
        "realtimeInputConfig.automaticActivityDetection.disabled is not supported: ADK Java live " +
          "connections cannot send ActivityStart or ActivityEnd"
      )
    }
    if (request.liveConnectConfig.explicitVadSignal == true) {
      throw UnsupportedOperationException(
        "explicitVadSignal = true is not supported: the server sends voice-activity messages, " +
          "which ADK Java live connections turn into errors"
      )
    }
    val javaRequest = LlmRequestCodec.toJavaLive(request)
    val opened = AtomicReference<LiveConnection?>()
    try {
      return withContext(dispatcher) {
        val javaConnection = javaLlm.connect(javaRequest)
        val connection =
          try {
            JavaLlmConnectionToKt(javaConnection, dispatcher)
          } catch (e: Throwable) {
            // The Java connection is already open, so a failure here must not leak it.
            try {
              withContext(NonCancellable) { closeJavaConnection(javaConnection, dispatcher) }
            } catch (closeFailure: Throwable) {
              e.addSuppressed(closeFailure)
            }
            throw e
          }
        connection.also { opened.set(it) }
      }
    } catch (e: CancellationException) {
      // A close failure must not replace the caller's cancellation.
      val connection = opened.get()
      if (connection != null) {
        try {
          connection.closeSession()
        } catch (closeFailure: Throwable) {
          e.addSuppressed(closeFailure)
        }
      }
      throw e
    }
  }
}
