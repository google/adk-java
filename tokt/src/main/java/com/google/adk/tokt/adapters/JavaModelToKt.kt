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

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.tokt.codecs.LlmRequestCodec
import com.google.adk.tokt.codecs.LlmResponseCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow

/**
 * Java -> Kotlin adapter: presents a user's ADK Java [JavaBaseLlm] as the Kotlin's [Model] so the
 * ADK Kotlin agent loop can call it. The model seam for routing ADK Java's `LlmAgent` onto the
 * Kotlin.
 *
 * The Kotlin `LlmRequest` is converted to a Java `LlmRequest` ([LlmRequestCodec]), the Java model's
 * RxJava `Flowable<LlmResponse>` is consumed as a coroutine [Flow] (via
 * kotlinx-coroutines-reactive) and each response is converted back ([LlmResponseCodec]).
 */
internal class JavaModelToKt(private val javaLlm: JavaBaseLlm) : Model {

  override val name: String = javaLlm.model()

  // Deferred into `flow {}` and dispatched on IO: the Java model's request build + generation run
  // off the engine dispatcher (RxJava is synchronous by default), and a synchronous throw is routed
  // through the Flow's error channel rather than escaping at collection time.
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    flow {
        emitAll(
          javaLlm.generateContent(LlmRequestCodec.toJava(request), stream).asFlow().map {
            LlmResponseCodec.fromJava(it)
          }
        )
      }
      .flowOn(Dispatchers.IO)
}
