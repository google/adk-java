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

import com.google.adk.events.ToolConfirmation as JavaToolConfirmation
import com.google.adk.kt.events.ToolConfirmation as KtToolConfirmation

/**
 * Converts a [ToolConfirmation][JavaToolConfirmation] between the ADK Java facade and the ADK
 * Kotlin, so a Java tool's `toolContext.requestConfirmation(...)` request reaches the Kotlin's
 * confirmation flow.
 */
internal object ToolConfirmationCodec {

  /** Returns the Java [JavaToolConfirmation] view of the Kotlin [confirmation]. */
  fun toJava(confirmation: KtToolConfirmation): JavaToolConfirmation =
    JavaToolConfirmation.builder()
      .confirmed(confirmation.confirmed)
      .hint(confirmation.hint)
      .payload(confirmation.payload)
      .build()

  /** Returns the Kotlin [KtToolConfirmation] view of the Java [confirmation]. */
  fun toKotlin(confirmation: JavaToolConfirmation): KtToolConfirmation =
    KtToolConfirmation(
      confirmed = confirmation.confirmed(),
      payload = confirmation.payload(),
      hint = confirmation.hint(),
    )
}
