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

package com.google.adk.tokt.codecs

import com.google.adk.JsonBaseModel
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content as KtContent
import com.google.adk.kt.workflow.NodeInfo as KtNodeInfo
import com.google.adk.kt.workflow.Route as KtRoute
import com.google.adk.workflow.NodeInfo as JavaNodeInfo
import com.google.adk.workflow.Route as JavaRoute
import com.google.genai.types.Content as GenaiContent

/**
 * Converts a workflow node's output, its [NodeInfo][KtNodeInfo], and the routes on `actions.route`
 * between ADK Kotlin and the ADK Java facade.
 */
internal object WorkflowCodec {

  /**
   * Returns the Java form of a node's [output]: a Kotlin `Content` becomes a genai `Content`, and a
   * kotlinx `JsonElement` becomes the plain maps, lists, and values of an event read from JSON.
   * Maps and lists are copied with each entry converted; a value with no JSON form throws.
   */
  @OptIn(FrameworkInternalApi::class)
  fun outputToJava(output: Any?): Any? =
    when (output) {
      null,
      is String,
      is Boolean,
      is Number -> output
      is KtContent -> ContentCodec.toJava(output)
      // A JsonObject or JsonArray is also a Map or List, so it converts entry by entry here.
      is Map<*, *> ->
        output.entries.associate { (key, value) -> key.toString() to outputToJava(value) }
      is List<*> -> output.map { outputToJava(it) }
      // Unwraps a JSON primitive or JSON null; throws if a value has no JSON form.
      else -> jsonElementToAny(anyToJsonElement(output))
    }

  /**
   * Returns the Kotlin form of a node's [output]: a genai `Content` becomes a Kotlin `Content`, and
   * any other non-JSON-native object becomes its JSON form, as in an event read from JSON. Maps and
   * lists are copied with each entry converted.
   */
  fun outputFromJava(output: Any?): Any? =
    when (output) {
      null,
      is String,
      is Boolean,
      is Int,
      is Long,
      is Float,
      is Double,
      is Short,
      is Byte -> output
      // ADK Kotlin has no JSON form for BigDecimal or BigInteger, so convert to Long or Double.
      is Number -> output.toString().toLongOrNull() ?: output.toDouble()
      is GenaiContent -> ContentCodec.fromJava(output)
      is Map<*, *> ->
        output.entries.associate { (key, value) -> key.toString() to outputFromJava(value) }
      is List<*> -> output.map { outputFromJava(it) }
      is Array<*> -> output.map { outputFromJava(it) }
      // Write, then read: convertValue can return an embedded value, such as a byte[], unchanged.
      else ->
        outputFromJava(
          JsonBaseModel.getMapper().let {
            it.readValue(it.writeValueAsString(output), Any::class.java)
          }
        )
    }

  /** Returns the Java [JavaNodeInfo] copy of the Kotlin [info]. */
  fun nodeInfoToJava(info: KtNodeInfo): JavaNodeInfo =
    JavaNodeInfo.builder()
      .path(info.path)
      .outputFor(info.outputFor.orEmpty())
      .messageAsOutput(info.messageAsOutput)
      .build()

  /**
   * Returns the Kotlin [KtNodeInfo] copy of the Java [info]. An empty `outputFor` reads as null,
   * the Kotlin default, since the Java list is never null.
   */
  fun nodeInfoFromJava(info: JavaNodeInfo): KtNodeInfo =
    KtNodeInfo(
      path = info.path(),
      outputFor = info.outputFor().ifEmpty { null },
      messageAsOutput = info.messageAsOutput(),
    )

  /** Returns the Java [JavaRoute] copy of the Kotlin [route]. */
  fun routeToJava(route: KtRoute): JavaRoute =
    when (route) {
      is KtRoute.Tag -> JavaRoute.Tag(route.value)
      is KtRoute.Num -> JavaRoute.Num(route.value)
      is KtRoute.Flag -> JavaRoute.Flag(route.value)
      KtRoute.Default -> JavaRoute.Default.INSTANCE
    }

  /** Returns the Kotlin [KtRoute] copy of the Java [route]. */
  fun routeFromJava(route: JavaRoute): KtRoute =
    when (route) {
      is JavaRoute.Tag -> KtRoute.Tag(route.value())
      is JavaRoute.Num -> KtRoute.Num(route.value())
      is JavaRoute.Flag -> KtRoute.Flag(route.value())
      is JavaRoute.Default -> KtRoute.Default
    }
}
